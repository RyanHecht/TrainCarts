package com.bergerkiller.bukkit.tc.attachments.control;

import java.util.ArrayList;
import java.util.Collection;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import com.bergerkiller.bukkit.common.map.MapEventPropagation;
import com.bergerkiller.bukkit.common.map.MapTexture;
import com.bergerkiller.bukkit.common.map.widgets.MapWidgetButton;
import com.bergerkiller.bukkit.common.map.widgets.MapWidgetTabView;
import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.math.Quaternion;
import com.bergerkiller.bukkit.common.resources.CommonSounds;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.common.utils.PlayerUtil;
import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.attachments.VirtualEntity;
import com.bergerkiller.bukkit.tc.attachments.VirtualEntity.SyncMode;
import com.bergerkiller.bukkit.tc.attachments.api.Attachment;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentInternalState;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentType;
import com.bergerkiller.bukkit.tc.attachments.config.ObjectPosition;
import com.bergerkiller.bukkit.tc.attachments.control.seat.SeatedEntity;
import com.bergerkiller.bukkit.tc.attachments.control.seat.SeatedEntity.DisplayMode;
import com.bergerkiller.bukkit.tc.attachments.control.seat.ThirdPersonDefault;
import com.bergerkiller.bukkit.tc.attachments.control.seat.FirstPersonDefault;
import com.bergerkiller.bukkit.tc.attachments.control.seat.SeatOrientation;
import com.bergerkiller.bukkit.tc.attachments.ui.MapWidgetAttachmentNode;
import com.bergerkiller.bukkit.tc.attachments.ui.MapWidgetToggleButton;
import com.bergerkiller.bukkit.tc.attachments.ui.menus.appearance.SeatExitPositionMenu;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberNetwork;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.generated.net.minecraft.server.EntityHandle;
import com.bergerkiller.generated.net.minecraft.server.EntityLivingHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutPositionHandle;

public class CartAttachmentSeat extends CartAttachment {
    public static final AttachmentType TYPE = new AttachmentType() {
        @Override
        public String getID() {
            return "SEAT";
        }

        @Override
        public MapTexture getIcon(ConfigurationNode config) {
            return MapTexture.loadPluginResource(TrainCarts.plugin, "com/bergerkiller/bukkit/tc/textures/attachments/seat.png");
        }

        @Override
        public Attachment createController(ConfigurationNode config) {
            return new CartAttachmentSeat();
        }

        @Override
        public void createAppearanceTab(MapWidgetTabView.Tab tab, MapWidgetAttachmentNode attachment) {
            tab.addWidget(new MapWidgetToggleButton<Boolean>() {
                @Override
                public void onSelectionChanged() {
                    attachment.getConfig().set("lockRotation", this.getSelectedOption());
                    sendStatusChange(MapEventPropagation.DOWNSTREAM, "changed");
                    attachment.resetIcon();
                    display.playSound(CommonSounds.CLICK);
                }
            }).addOptions(b -> "Lock Rotation: " + (b ? "ON" : "OFF"), Boolean.TRUE, Boolean.FALSE)
              .setSelectedOption(attachment.getConfig().get("lockRotation", false))
              .setBounds(0, 10, 100, 16);

            tab.addWidget(new MapWidgetToggleButton<ViewLockMode>() {
                @Override
                public void onSelectionChanged() {
                    attachment.getConfig().set("lockView", this.getSelectedOption());
                    sendStatusChange(MapEventPropagation.DOWNSTREAM, "changed");
                    attachment.resetIcon();
                    display.playSound(CommonSounds.CLICK);
                }
            }).addOptions(o -> "Lock View: " + o.name(), ViewLockMode.class)
              .setSelectedOption(attachment.getConfig().get("lockView", ViewLockMode.OFF))
              .setBounds(0, 28, 100, 16);

            tab.addWidget(new MapWidgetToggleButton<DisplayMode>() {
                @Override
                public void onSelectionChanged() {
                    attachment.getConfig().set("displayMode", this.getSelectedOption());
                    sendStatusChange(MapEventPropagation.DOWNSTREAM, "changed");
                    attachment.resetIcon();
                    display.playSound(CommonSounds.CLICK);
                }
            }).addOptions(o -> "Display: " + o.name(), DisplayMode.class)
              .setSelectedOption(attachment.getConfig().get("displayMode", DisplayMode.DEFAULT))
              .setBounds(0, 46, 100, 16);

            tab.addWidget(new MapWidgetButton() { // Change exit position button
                @Override
                public void onActivate() {
                    //TODO: Cleaner way to open a sub dialog
                    tab.getParent().getParent().addWidget(new SeatExitPositionMenu()).setAttachment(attachment);
                }
            }).setText("Change Exit").setBounds(0, 64, 100, 16);
        }
    };

    // Houses the logic for synchronizing this seat to players viewing the entity in first person
    // That is, the viewer is the one inside this seat
    private FirstPersonDefault firstPerson = new FirstPersonDefault(this);
    // Houses the logic for synchronizing this seat to players viewing the entity in third person
    // That is, the viewer is not the one inside this seat
    private ThirdPersonDefault thirdPerson = new ThirdPersonDefault(this);

    /**
     * Information about the entity that is seated inside this seat
     */
    public final SeatedEntity seated = new SeatedEntity();

    // The fake mount is used when this seat has a position set, or otherwise cannot
    // mount the passenger to a parent attachment. The _parentMountId is set to the
    // entity id of the vehicle this passenger is mounted to.
    private VirtualEntity _fakeMount = null;
    public int _parentMountId = -1;

    // During makeVisible(viewer) this is set to that viewer, to ignore it when refreshing
    private Player _makeVisibleCurrent = null;

    // Remainder yaw and pitch when moving player view orientation along with the seat
    // This remainder is here because Minecraft has only limited yaw/pitch granularity
    private double _playerYawRemainder = 0.0;
    private double _playerPitchRemainder = 0.0;

    // Seat configuration
    private ViewLockMode _viewLockMode = ViewLockMode.OFF;
    private ObjectPosition _ejectPosition = new ObjectPosition();
    private boolean _ejectLockRotation = false;

    /**
     * Gets the viewers of this seat that have already had makeVisible processed.
     * The entity passed to makeVisible() is removed from the list during
     * makeVisible().
     * 
     * @return synced viewers
     */
    public Collection<Player> getViewersSynced() {
        if (_makeVisibleCurrent == null) {
            return this.getViewers();
        } else {
            ArrayList<Player> tmp = new ArrayList<Player>(this.getViewers());
            tmp.remove(_makeVisibleCurrent);
            return tmp;
        }
    }

    @Override
    public void onAttached() {
        super.onAttached();

        this.seated.orientation.setLocked(this.getConfig().get("lockRotation", false));
        this._viewLockMode = this.getConfig().get("lockView", ViewLockMode.OFF);
        this.seated.setDisplayMode(this.getConfig().get("displayMode", DisplayMode.DEFAULT));

        ConfigurationNode ejectPosition = this.getConfig().getNode("ejectPosition");
        this._ejectPosition.load(ejectPosition);
        this._ejectLockRotation = ejectPosition.get("lockRotation", false);
    }

    @Override
    public void onDetached() {
        super.onDetached();
        this.setEntity(null);
    }

    @Override
    public void makeVisible(Player viewer) {
        try {
            this._makeVisibleCurrent = viewer;
            updateMode(false);
            makeVisibleImpl(viewer);
        } finally {
            this._makeVisibleCurrent = null;
        }
    }

    private void makeVisibleImpl(Player viewer) {
        if (seated.isEmpty()) {
            return;
        }

        // Find a parent to mount to
        if (this._parentMountId == -1) {
            // Use parent node for mounting point, unless not possible or we have a position set for the seat
            if (this.getParent() != null && this.getConfiguredPosition().isDefault()) {
                this._parentMountId = ((CartAttachment) this.getParent()).getMountEntityId();
            }

            // No parent node mount is used, create a fake mount
            if (this._parentMountId == -1) {
                if (this._fakeMount == null) {
                    this._fakeMount = new VirtualEntity(this.getManager());
                    this._fakeMount.setEntityType(EntityType.CHICKEN);
                    this._fakeMount.setSyncMode(SyncMode.SEAT);
                    this._fakeMount.setRelativeOffset(this.seated.orientation.getMountOffset());

                    // Put the entity on a fake mount that we move around at an offset
                    this._fakeMount.updatePosition(this.getTransform(), new Vector(0.0, (double) this.seated.orientation.getMountYaw(), 0.0));
                    this._fakeMount.syncPosition(true);
                    this._fakeMount.getMetaData().set(EntityHandle.DATA_FLAGS, (byte) (EntityHandle.DATA_FLAG_INVISIBLE));
                    this._fakeMount.getMetaData().set(EntityLivingHandle.DATA_HEALTH, 10.0F);
                }
                this._parentMountId = this._fakeMount.getEntityId();
            }
        }

        // Spawn fake mount, if used
        if (this._fakeMount != null) {
            this._fakeMount.spawn(viewer, calcMotion());
        }

        if (viewer == this.seated.getEntity()) {
            this.firstPerson.makeVisible(viewer);
        } else {
            this.thirdPerson.makeVisible(viewer);
        }

        // If rotation locked, send the rotation of the passenger if available
        this.seated.orientation.makeVisible(viewer, this.seated);
    }

    @Override
    public void makeHidden(Player viewer) {
        if (this.seated.getEntity() == viewer) {
            this.firstPerson.makeHidden(viewer);
        } else {
            this.thirdPerson.makeHidden(viewer);
        }

        if (this._fakeMount != null) {
            this._fakeMount.destroy(viewer);
        }
    }

    public Vector calcMotion() {
        AttachmentInternalState state = this.getInternalState();
        Vector pos_old = state.last_transform.toVector();
        Vector pos_new = state.curr_transform.toVector();
        return pos_new.subtract(pos_old);
    }

    @Override
    public void onTransformChanged(Matrix4x4 transform) {
        if (this._fakeMount != null &&
            this.getConfiguredPosition().isDefault() &&
            this.getParent() != null)
        {
            this.getParent().applyDefaultSeatTransform(transform);
        }

        // Synchronize orientation of the entity inside this seat
        this.seated.orientation.synchronize(this, transform, this.seated);

        // Apply rotation to fake mount, if needed
        if (this._fakeMount != null) {
            this._fakeMount.setRelativeOffset(this.seated.orientation.getMountOffset());
            this._fakeMount.updatePosition(transform, new Vector(0.0, (double) this.seated.orientation.getMountYaw(), 0.0));
        }
    }

    @Override
    public void onMove(boolean absolute) {
        // Move the first-person view, if needed
        if (seated.isPlayer() && this.getViewers().contains(seated.getEntity())) {
            firstPerson.onMove(absolute);
        }

        // If not parented to a parent attachment, move the fake mount to move the seat
        if (this._fakeMount != null) {
            this._fakeMount.syncPosition(absolute);
        }
    }

    /**
     * Gets the Entity that is displayed and controlled in this seat
     * 
     * @return seated entity
     */
    public Entity getEntity() {
        return this.seated.getEntity();
    }

    /**
     * Sets the Entity that is displayed and controlled.
     * Any previously set entity is reset to the defaults.
     * The new entity has seated entity specific settings applied to it.
     * 
     * @param entity to set to
     */
    public void setEntity(Entity entity) {
        if (seated.getEntity() == entity) {
            return;
        }

        if (!this.seated.isEmpty()) {
            // If a previous entity was set, unseat it
            for (Player viewer : this.getViewers()) {
                PlayerUtil.getVehicleMountController(viewer).unmount(this._parentMountId, this.seated.getEntity().getEntityId());
                this.makeHidden(viewer);
            }
            TrainCarts.plugin.getSeatAttachmentMap().remove(this.seated.getEntity().getEntityId(), this);
        }

        // Switch entity
        this.seated.setEntity(entity);

        // Initialize mode with this new Entity
        this.updateMode(true);

        // Re-seat new entity
        if (!this.seated.isEmpty()) {
            TrainCarts.plugin.getSeatAttachmentMap().set(this.seated.getEntity().getEntityId(), this);
            for (Player viewer : this.getViewers()) {
                this.makeVisibleImpl(viewer);
            }
        }
    }

    @Override
    public void onTick() {
        // Only needed when there is a passenger
        this.updateMode(false);

        // Move player view relatively
        if (this._viewLockMode == ViewLockMode.MOVE && this.seated.isPlayer()) {
            Vector old_pyr;
            {
                Location eye_loc = ((Player) this.seated.getEntity()).getEyeLocation();
                old_pyr = new Vector(eye_loc.getPitch() + this._playerPitchRemainder,
                                     eye_loc.getYaw() + this._playerYawRemainder,
                                     0.0);
                old_pyr.setX(-old_pyr.getX());
            }

            // Compute the new rotation of the player with the current tick rotation of this attachment applied
            // TODO: Should we somehow eliminate roll?
            Quaternion rotation = Matrix4x4.diffRotation(this.getPreviousTransform(), this.getTransform());
            rotation.multiply(Quaternion.fromYawPitchRoll(old_pyr));

            // Compute change in yaw/pitch/roll
            Vector pyr = rotation.getYawPitchRoll().subtract(old_pyr);
            pyr.setX(MathUtil.wrapAngle(pyr.getX()));
            pyr.setY(MathUtil.wrapAngle(pyr.getY()));
            pyr.setX(-pyr.getX());

            // Refresh this change in pitch/yaw/roll to the player
            if (Math.abs(pyr.getX()) > 1e-5 || Math.abs(pyr.getY()) > 1e-5) {
                PacketPlayOutPositionHandle p = PacketPlayOutPositionHandle.createRelative(0.0, 0.0, 0.0, (float) pyr.getY(), (float) pyr.getX());
                this._playerPitchRemainder = (pyr.getX() - p.getPitch());
                this._playerYawRemainder = (pyr.getY() - p.getYaw());
                PacketUtil.sendPacket((Player) this.seated.getEntity(), p);
            } else {
                this._playerPitchRemainder = pyr.getX();
                this._playerYawRemainder = pyr.getY();
            }
        }
    }

    /*
     * Copied from BKCommonLib 1.15.2 Quaternion getPitch()
     * Once we depend on 1.15.2 or later, this can be removed and replaced with transform.getRotationPitch()
     */
    private static double getQuaternionPitch(double x, double y, double z, double w) {
        final double test = 2.0 * (w * x - y * z);
        if (Math.abs(test) < (1.0 - 1E-15)) {
            double pitch = Math.asin(test);
            double roll_x = 0.5 - (x * x + z * z);
            if (roll_x <= 0.0 && (Math.abs((w * z + x * y)) > roll_x)) {
                pitch = -pitch;
                pitch += (pitch < 0.0) ? Math.PI : -Math.PI;
            }
            return Math.toDegrees(pitch);
        } else if (test < 0.0) {
            return -90.0;
        } else {
            return 90.0;
        }
    }

    private void updateMode(boolean silent) {
        // Compute new first-person state of whether the player sees himself from third person using a fake camera
        boolean new_virtualCam;

        // Whether a fake entity is used to represent this seated entity
        boolean new_isFake;

        // Whether the (fake) entity is displayed upside-down
        boolean new_isUpsideDown;

        if (this.seated.isEmpty()) {
            new_virtualCam = false;
            new_isFake = false;
            new_isUpsideDown = false;
        } else if (this.seated.getDisplayMode() == DisplayMode.ELYTRA || this.seated.getDisplayMode() == DisplayMode.ELYTRA_SIT) {
            Quaternion rotation = this.getTransform().getRotation();
            double selfPitch = getQuaternionPitch(rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW());

            new_isUpsideDown = false;

            new_virtualCam = TCConfig.enableSeatThirdPersonView &&
                             this.seated.isPlayer() &&
                             true; //Math.abs(selfPitch) > 70.0;

            new_isFake = this.seated.isPlayer();
        } else {
            Quaternion rotation = this.getTransform().getRotation();
            double selfPitch = getQuaternionPitch(rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW());

            // Compute new upside-down state
            new_isUpsideDown = this.seated.isUpsideDown();
            if (MathUtil.getAngleDifference(selfPitch, 180.0) < 89.0) {
                // Beyond the point where the entity should be rendered upside-down
                new_isUpsideDown = true;
            } else if (MathUtil.getAngleDifference(selfPitch, 0.0) < 89.0) {
                // Beyond the point where the entity should be rendered normally again
                new_isUpsideDown = false;
            }

            // Compute new first-person state of whether the player sees himself from third person using a fake camera
            new_virtualCam = TCConfig.enableSeatThirdPersonView &&
                             this.seated.isPlayer() &&
                             Math.abs(selfPitch) > 70.0;

            // Whether a fake entity is used to represent this seated entity
            new_isFake = this.seated.isPlayer() && (new_isUpsideDown || new_virtualCam);
        }

        // When we change whether a fake entity is displayed, hide for everyone and make visible again
        if (silent) {
            // Explicitly requested we do not send any packets
            this.seated.setFake(new_isFake);
            this.seated.setUpsideDown(new_isUpsideDown);
            this.firstPerson.setUseVirtualCamera(new_virtualCam);
            return;
        }

        if (new_isFake != this.seated.isFake() || (this.seated.isPlayer() && new_isUpsideDown != this.seated.isUpsideDown())) {
            // Fake entity changed, this requires the entity to be respawned for everyone
            // When upside-down changes for a Player seated entity, also perform a respawn
            Collection<Player> viewers = this.getViewersSynced();
            for (Player viewer : viewers) {
                this.makeHidden(viewer);
            }
            this.seated.setFake(new_isFake);
            this.seated.setUpsideDown(new_isUpsideDown);
            this.firstPerson.setUseVirtualCamera(new_virtualCam);
            for (Player viewer : viewers) {
                this.makeVisibleImpl(viewer);
            }
        } else {
            if (new_isUpsideDown != this.seated.isUpsideDown()) {
                // Upside-down changed, but the seated entity is not a Player
                // All we have to do is refresh the Entity metadata
                this.seated.setUpsideDown(new_isUpsideDown);
                if (!this.seated.isEmpty()) {
                    for (Player viewer : this.getViewersSynced()) {
                        this.seated.refreshMetadata(viewer);
                    }
                }
            }
            if (new_virtualCam != this.firstPerson.useVirtualCamera()) {
                // Only first-person view useVirtualCamera changed
                Collection<Player> viewers = this.getViewersSynced();
                if (viewers.contains(this.seated.getEntity())) {
                    // Hide, change, and make visible again, just for the first-player-view player
                    Player viewer = (Player) this.seated.getEntity();
                    this.makeHidden(viewer);
                    this.firstPerson.setUseVirtualCamera(new_virtualCam);
                    this.makeVisibleImpl(viewer);
                } else {
                    // Silent
                    this.firstPerson.setUseVirtualCamera(new_virtualCam);
                }
            }
        }
    }

    /**
     * Gets whether the seated entity is displayed sitting upside-down
     * 
     * @return True if upside-down
     */
    public boolean isUpsideDown() {
        return this.seated.isUpsideDown();
    }

    /**
     * Whether the passengers inside have their rotation locked based on the orientation of this seat
     * 
     * @return True if rotation is locked
     */
    public boolean isRotationLocked() {
        return this.seated.orientation.isLocked();
    }

    public float getPassengerYaw() {
        return this.seated.orientation.getPassengerYaw();
    }

    public float getPassengerPitch() {
        return this.seated.orientation.getPassengerPitch();
    }

    public float getPassengerHeadYaw() {
        return this.seated.orientation.getPassengerHeadYaw();
    }

    /**
     * Calculates the eject position of the seat
     * 
     * @param passenger to check eject position for
     * @return eject position
     */
    public Location getEjectPosition(Entity passenger) {
        Matrix4x4 tmp = this.getTransform().clone();
        this._ejectPosition.anchor.apply(this, tmp);

        // If this is inside a Minecart, check the exit offset / rotation properties
        if (this.getManager() instanceof MinecartMemberNetwork) {
            CartProperties cprop = ((MinecartMemberNetwork) this.getManager()).getMember().getProperties();

            // Translate eject offset specified in the cart's properties
            tmp.translate(cprop.exitOffset);

            // Apply transformation of eject position (translation, then rotation)
            tmp.multiply(this._ejectPosition.transform);

            // Apply eject rotation specified in the cart's properties on top
            tmp.rotateYawPitchRoll(cprop.exitPitch, cprop.exitYaw, 0.0f);
        } else {
            // Only use the eject position transform
            tmp.multiply(this._ejectPosition.transform);
        }

        org.bukkit.World w = this.getManager().getWorld();
        Vector pos = tmp.toVector();
        Vector ypr = tmp.getYawPitchRoll();
        float yaw = (float) ypr.getY();
        float pitch = (float) ypr.getX();

        // When rotation is not locked, preserve original orientation of passenger
        if (!this._ejectLockRotation && passenger != null) {
            Location curr_loc;
            if (passenger instanceof LivingEntity) {
                curr_loc = ((LivingEntity) passenger).getEyeLocation();
            } else {
                curr_loc = passenger.getLocation();
            }
            yaw = curr_loc.getYaw();
            pitch = curr_loc.getPitch();
        }

        return new Location(w, pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    @Override
    public boolean isHiddenWhenInactive() {
        return false;
    }

    public static enum ViewLockMode {
        OFF, /* Player view orientation is not changed */
        MOVE, /* Player view orientation moves along as the seat moves */
        //LOCK /* Player view is locked to look forwards in the seat direction at all times */
    }
}
