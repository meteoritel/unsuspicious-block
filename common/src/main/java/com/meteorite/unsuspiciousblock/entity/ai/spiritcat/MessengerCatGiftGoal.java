package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 猫猫信使配送阶段机，统一驱动接近、重新定位、致意、投放和消散。
 */
public class MessengerCatGiftGoal extends Goal {
    private static final double RELOCATE_DISTANCE_SQR = 32.0 * 32.0;
    private static final double ARRIVAL_DISTANCE_SQR = 1.25 * 1.25;
    private static final int MAX_RELOCATIONS = 2;
    private static final int PHASE_AFTER_BLOCKED_TICKS = 40;

    private final MessengerCat cat;
    private Player target;
    private double approachSide = 1.0;

    public MessengerCatGiftGoal(MessengerCat cat) {
        this.cat = cat;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.cat.isDutyActive() && this.cat.getBehavior() != null && this.resolveTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.cat.isDutyActive() && !this.cat.isRemoved() && this.cat.getBehavior() != null;
    }

    @Override
    public void start() {
        this.approachSide = this.cat.getRandom().nextBoolean() ? 1.0 : -1.0;
        this.cat.beginLifecycle();
    }

    @Override
    public void tick() {
        MessengerCatBehavior behavior = this.cat.getBehavior();
        if (behavior == null || !(this.cat.level() instanceof ServerLevel level)) {
            return;
        }
        this.cat.advanceTick();
        this.target = this.resolveTarget();
        if (this.target == null && this.cat.getPhase() != MessengerCatPhase.DISSIPATE) {
            this.cat.setPhase(MessengerCatPhase.DISSIPATE);
        }
        if (this.cat.getTotalTicks() >= behavior.getMaxLifetime() - behavior.getDissipateDuration()
                && this.cat.getPhase() != MessengerCatPhase.DISSIPATE) {
            this.cat.setPhase(MessengerCatPhase.DISSIPATE);
        }

        int phaseTicks = this.cat.getPhaseTicks();
        switch (this.cat.getPhase()) {
            case MANIFEST -> this.tickManifest(level, behavior, phaseTicks);
            case APPROACH -> this.tickApproach(level, behavior);
            case RELOCATE -> this.tickRelocate(level, behavior, phaseTicks);
            case GREET -> this.tickGreet(level, behavior, phaseTicks);
            case DELIVER -> this.tickDeliver(level, behavior);
            case FAREWELL -> this.tickFarewell(level, behavior, phaseTicks);
            case DISSIPATE -> this.tickDissipate(level, behavior, phaseTicks);
        }
    }

    private void tickManifest(ServerLevel level, MessengerCatBehavior behavior, int phaseTicks) {
        this.cat.getFlightController().stop();
        if (phaseTicks == 1) {
            behavior.onManifestStart(this.cat, level);
        }
        Entity guide = this.resolveGuide(level, behavior.getGuideUuid());
        if (guide != null && phaseTicks <= behavior.getManifestDuration() / 2) {
            this.cat.getLookControl().setLookAt(guide);
        } else if (this.target != null) {
            this.cat.getLookControl().setLookAt(this.target);
        }
        if (phaseTicks >= behavior.getManifestDuration()) {
            this.cat.setPhase(this.target == null ? MessengerCatPhase.DISSIPATE : MessengerCatPhase.APPROACH);
        }
    }

    private void tickApproach(ServerLevel level, MessengerCatBehavior behavior) {
        if (this.target == null) {
            this.cat.setPhase(MessengerCatPhase.DISSIPATE);
            return;
        }
        if (this.cat.distanceToSqr(this.target) > RELOCATE_DISTANCE_SQR) {
            this.cat.setPhase(this.cat.getRelocationCount() < MAX_RELOCATIONS
                    ? MessengerCatPhase.RELOCATE : MessengerCatPhase.DISSIPATE);
            return;
        }
        behavior.onApproachTick(this.cat, level);
        Vec3 stop = this.getStopPosition(this.target);
        Vec3 offset = stop.subtract(this.cat.position());
        double distance = offset.length();
        if (offset.lengthSqr() <= ARRIVAL_DISTANCE_SQR) {
            this.cat.getFlightController().stop();
            this.cat.setPhase(behavior.shouldGreet() ? MessengerCatPhase.GREET : MessengerCatPhase.DELIVER);
            return;
        }
        Vec3 horizontal = new Vec3(offset.x, 0.0, offset.z);
        Vec3 arc = horizontal.lengthSqr() < 0.0001
                ? Vec3.ZERO
                : new Vec3(-horizontal.z, 0.0, horizontal.x).normalize()
                .scale(Math.min(1.5, distance * 0.2) * this.approachSide);
        double speed = distance > 16.0 ? 0.5 : distance > 6.0 ? 0.3 : 0.18;
        this.cat.getFlightController().moveToward(stop.add(arc), speed, false, PHASE_AFTER_BLOCKED_TICKS);
        this.cat.getLookControl().setLookAt(this.target);
    }

    private void tickRelocate(ServerLevel level, MessengerCatBehavior behavior, int phaseTicks) {
        this.cat.getFlightController().stop();
        int midpoint = Math.max(1, behavior.getRelocateDuration() / 2);
        if (phaseTicks == midpoint && this.target != null) {
            MessengerCatPositioning.placeNearTarget(level, this.cat, this.target);
            this.cat.incrementRelocationCount();
            behavior.onManifestStart(this.cat, level);
        }
        if (phaseTicks >= behavior.getRelocateDuration()) {
            this.cat.setPhase(this.target == null ? MessengerCatPhase.DISSIPATE : MessengerCatPhase.APPROACH);
        }
    }

    private void tickGreet(ServerLevel level, MessengerCatBehavior behavior, int phaseTicks) {
        this.cat.getFlightController().stop();
        if (this.target == null) {
            this.cat.setPhase(MessengerCatPhase.DISSIPATE);
            return;
        }
        if (this.cat.distanceToSqr(this.getStopPosition(this.target)) > 6.25) {
            this.cat.setPhase(MessengerCatPhase.APPROACH);
            return;
        }
        this.cat.getLookControl().setLookAt(this.target);
        behavior.onGreetTick(this.cat, level, phaseTicks);
        if (phaseTicks >= behavior.getGreetDuration()) {
            this.cat.setPhase(MessengerCatPhase.DELIVER);
        }
    }

    private void tickDeliver(ServerLevel level, MessengerCatBehavior behavior) {
        boolean delivered = this.target != null && behavior.shouldDeliverGift()
                && behavior.onDeliver(this.cat, level);
        this.cat.setDeliverySucceeded(delivered);
        this.cat.setPhase(delivered ? MessengerCatPhase.FAREWELL : MessengerCatPhase.DISSIPATE);
    }

    private void tickFarewell(ServerLevel level, MessengerCatBehavior behavior, int phaseTicks) {
        this.cat.getFlightController().stop();
        if (phaseTicks == 1) {
            behavior.onFarewellStart(this.cat, level);
        }
        if (this.target != null) {
            this.cat.getLookControl().setLookAt(this.target);
        }
        if (phaseTicks >= behavior.getFarewellDuration()) {
            this.cat.setPhase(MessengerCatPhase.DISSIPATE);
        }
    }

    private void tickDissipate(ServerLevel level, MessengerCatBehavior behavior, int phaseTicks) {
        if (phaseTicks == 1) {
            behavior.onDissipateStart(this.cat, level);
        }
        this.cat.setDeltaMovement(0.0, 0.04, 0.0);
        if (phaseTicks >= behavior.getDissipateDuration()) {
            this.cat.discard();
        }
    }

    private Vec3 getStopPosition(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 0.0001) {
            horizontal = new Vec3(0.0, 0.0, 1.0);
        }
        return player.position().add(horizontal.normalize().scale(2.5)).add(0.0, 1.45, 0.0);
    }

    private Player resolveTarget() {
        MessengerCatBehavior behavior = this.cat.getBehavior();
        if (behavior == null || !(this.cat.level() instanceof ServerLevel level)) {
            return null;
        }
        UUID uuid = behavior.getTargetUuid();
        Player player = uuid == null ? null : level.getPlayerByUUID(uuid);
        return player != null && player.isAlive() ? player : null;
    }

    private Entity resolveGuide(ServerLevel level, UUID guideUuid) {
        return guideUuid == null ? null : level.getEntity(guideUuid);
    }
}
