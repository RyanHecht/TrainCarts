package com.bergerkiller.bukkit.tc.actions;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.utils.TrackIterator;

public class MemberActionWaitOccupied extends MemberAction implements WaitAction {
	private BlockFace direction;
	private Block start;
	private final int maxsize;
	private final long delay;
	private final double launchDistance;
	private double launchforce;
	private int counter = 20;
	private boolean breakCode = false;
	public MemberActionWaitOccupied(final MinecartMember head, final int maxsize, final long delay, final double launchDistance) {
		super(head);
		this.maxsize = maxsize;
		this.direction = head.getDirectionTo();
		this.start = head.getRailsBlock();
		this.delay = delay;
		this.launchDistance = launchDistance;
	}

	@Override
	public void start() {
		if (this.handleOccupied()) {
			this.launchforce = this.getGroup().getAverageForce();
			this.getGroup().stop(true);
		} else {
			breakCode = true;
		}
	}

	public boolean handleOccupied() {
		return handleOccupied(this.start, this.direction, this.getMember(), this.maxsize);
	}
	public static boolean handleOccupied(Block start, BlockFace direction, MinecartMember ignore, int maxdistance) {
		TrackIterator iter = new TrackIterator(start, direction);
		while (iter.hasNext() && --maxdistance >= 0) {
			MinecartMember mm = MinecartMember.getAt(iter.next());
			if (mm != null && mm.getGroup() != ignore.getGroup()) {
				ignore.setIgnoreCollisions(true);
				return true;
			}
		}
		ignore.setIgnoreCollisions(false);
		return false;
	}

	@Override
	public boolean update() {
		if (breakCode) return true;
		if (counter++ >= 20) {
			if (!this.handleOccupied()) {
				//launch
				if (this.delay > 0) {
					this.getGroup().addActionWait(this.delay);
				}
				this.getMember().addActionLaunch(this.direction, this.launchDistance, this.launchforce);
				return true;
			} else {
				//this.wasoccupied = this.handleOccupied();
			}
			counter = 0;
		}
		return false;
	}

	@Override
	public boolean isVelocityChangesSuppressed() {
		return true;
	}
}
