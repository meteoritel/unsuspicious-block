package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.entity.SpiritCat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 猫国灵体通用飞行控制器，提供平滑转向、轻量避障、悬浮高度与受阻穿墙。
 */
public final class SpiritFlightController {
    private static final double HOVER_HEIGHT = 1.5;
    private static final int GROUND_PROBE_INTERVAL = 5;
    private static final int GROUND_PROBE_DEPTH = 8;
    private static final int PHASE_EXIT_CLEAR_TICKS = 6;
    private static final double ACCELERATION = 0.22;
    private static final double STOP_DAMPING = 0.65;
    private static final double MIN_DIRECTION_LENGTH_SQR = 0.0001;
    private static final double[] AVOIDANCE_ANGLES = {0.0, 45.0, -45.0, 90.0, -90.0};

    private final SpiritCat cat;
    private int blockedTicks;
    private int clearTicks;
    private int groundProbeCooldown;
    private double cachedHoverY = Double.NaN;

    public SpiritFlightController(SpiritCat cat) {
        this.cat = cat;
    }

    // 平滑飞向目标；groundAligned 为 true 时将目标高度贴合到附近表面上方。
    public void moveToward(Vec3 target, double maxSpeed, boolean groundAligned, int phaseAfterTicks) {
        if (!this.cat.isDutyActive()) {
            this.stop();
            return;
        }
        Vec3 adjustedTarget = groundAligned
                ? new Vec3(target.x, this.resolveHoverY(), target.z)
                : target;
        Vec3 desired = adjustedTarget.subtract(this.cat.position());
        if (desired.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            this.stop();
            return;
        }

        double speed = Math.max(0.0, maxSpeed);
        Vec3 directStep = desired.normalize().scale(speed);
        Vec3 selectedStep = directStep;
        if (this.cat.getSpiritMovementState() != SpiritMovementState.PHASE) {
            selectedStep = this.selectClearStep(directStep);
            if (selectedStep == null) {
                this.blockedTicks++;
                selectedStep = Vec3.ZERO;
                if (this.blockedTicks >= Math.max(1, phaseAfterTicks)) {
                    this.cat.setSpiritMovementState(SpiritMovementState.PHASE);
                    selectedStep = directStep;
                    this.clearTicks = 0;
                }
            } else {
                this.blockedTicks = 0;
            }
        } else {
            if (this.canOccupy(directStep)) {
                this.clearTicks++;
                if (this.clearTicks >= PHASE_EXIT_CLEAR_TICKS) {
                    this.cat.setSpiritMovementState(SpiritMovementState.FLY);
                    this.blockedTicks = 0;
                    this.clearTicks = 0;
                }
            } else {
                this.clearTicks = 0;
            }
        }

        Vec3 current = this.cat.getDeltaMovement();
        Vec3 next = current.lerp(selectedStep, ACCELERATION);
        this.cat.setDeltaMovement(next);
        if (this.cat.getSpiritMovementState() != SpiritMovementState.PHASE) {
            this.cat.setSpiritMovementState(SpiritMovementState.FLY);
        }
        this.faceMovement(next);
    }

    // 平滑停止并回到悬停状态。
    public void stop() {
        Vec3 slowed = this.cat.getDeltaMovement().scale(STOP_DAMPING);
        if (slowed.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            slowed = Vec3.ZERO;
        }
        this.cat.setDeltaMovement(slowed);
        this.blockedTicks = 0;
        this.clearTicks = 0;
        this.cat.setSpiritMovementState(SpiritMovementState.HOVER);
    }

    // 在不运行职责 AI 时立即清除残余速度。
    public void reset() {
        this.cat.setDeltaMovement(Vec3.ZERO);
        this.blockedTicks = 0;
        this.clearTicks = 0;
        this.groundProbeCooldown = 0;
        this.cachedHoverY = Double.NaN;
        this.cat.setSpiritMovementState(SpiritMovementState.HOVER);
    }

    private Vec3 selectClearStep(Vec3 directStep) {
        for (double angle : AVOIDANCE_ANGLES) {
            Vec3 candidate = angle == 0.0 ? directStep : directStep.yRot((float) Math.toRadians(angle));
            if (this.canOccupy(candidate)) {
                return candidate;
            }
        }
        Vec3 upward = new Vec3(directStep.x * 0.6, Math.max(0.2, directStep.y + 0.25), directStep.z * 0.6);
        return this.canOccupy(upward) ? upward : null;
    }

    private boolean canOccupy(Vec3 step) {
        return this.cat.level().noCollision(this.cat, this.cat.getBoundingBox().move(step));
    }

    private double resolveHoverY() {
        if (this.groundProbeCooldown > 0 && !Double.isNaN(this.cachedHoverY)) {
            this.groundProbeCooldown--;
            return this.cachedHoverY;
        }
        this.groundProbeCooldown = GROUND_PROBE_INTERVAL - 1;
        BlockPos origin = BlockPos.containing(this.cat.getX(), this.cat.getY() + 1.0, this.cat.getZ());
        for (int offset = 0; offset <= GROUND_PROBE_DEPTH; offset++) {
            BlockPos pos = origin.below(offset);
            BlockState state = this.cat.level().getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(this.cat.level(), pos);
            if (!shape.isEmpty()) {
                this.cachedHoverY = pos.getY() + shape.max(Direction.Axis.Y) + HOVER_HEIGHT;
                return this.cachedHoverY;
            }
        }
        this.cachedHoverY = Math.max(this.cat.getY(), this.cat.level().getMinBuildHeight() + HOVER_HEIGHT);
        return this.cachedHoverY;
    }

    private void faceMovement(Vec3 movement) {
        if (movement.horizontalDistanceSqr() < MIN_DIRECTION_LENGTH_SQR) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-movement.x, movement.z));
        this.cat.setYRot(yaw);
        this.cat.setYBodyRot(yaw);
    }
}
