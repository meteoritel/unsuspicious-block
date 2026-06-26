package com.meteorite.unsuspiciousblock.entity.ai.ghost;

import com.meteorite.unsuspiciousblock.entity.GhostCat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 幽灵猫阶段机驱动器 —— 按 {@link GhostCatBehavior} 配置推进生命周期阶段。
 * <p>
 * 阶段流转：MANIFEST → APPROACH → (GREET) → DELIVER → DISSIPATE。
 * 任一阶段中目标失效（离线/跨维度/距离 > 128）或总寿命耗尽，直接跳转 DISSIPATE（不赠礼）。
 * <p>
 * 接近阶段采用直线飘行 + 穿墙位移（由 {@link GhostCat#move} override 实现），
 * 目标点取玩家头顶上方（y+2.0），避免猫钻进玩家 hitbox。
 */
public class GhostCatGiftGoal extends Goal {

    // 送达判定距离的平方（约 3 格）：拉远以避免猫钻进玩家 hitbox 触发视觉/物理贴脸
    private static final double DELIVER_DISTANCE_SQR = 9.0;
    // 目标失效距离阈值（格）
    private static final double TARGET_LOST_DISTANCE = 128.0;

    private final GhostCat cat;
    private Player target;

    public GhostCatGiftGoal(GhostCat cat) {
        this.cat = cat;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.cat.getBehavior() != null && this.resolveTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.cat.isRemoved() && this.cat.getBehavior() != null;
    }

    @Override
    public void start() {
        // 重置生命周期，从显现阶段开始
        this.cat.beginLifecycle();
    }

    @Override
    public void tick() {
        GhostCatBehavior b = this.cat.getBehavior();
        if (b == null) {
            return;
        }
        if (!(this.cat.level() instanceof ServerLevel level)) {
            return;
        }

        // 推进计数（先增后判，使首 tick phaseTicks==1）
        this.cat.advanceTick();
        int phaseTicks = this.cat.getPhaseTicks();

        // 目标有效性检测
        this.target = this.resolveTarget();
        boolean targetValid = this.target != null
                && this.cat.distanceToSqr(this.target) < TARGET_LOST_DISTANCE * TARGET_LOST_DISTANCE;

        switch (this.cat.getPhase()) {
            case MANIFEST -> {
                if (phaseTicks == 1) {
                    b.onManifestStart(this.cat, level);
                }
                if (phaseTicks >= b.getManifestDuration()) {
                    this.cat.setPhase(targetValid ? GhostCatPhase.APPROACH : GhostCatPhase.DISSIPATE);
                }
            }
            case APPROACH -> {
                if (this.target != null) {
                    b.onApproachTick(this.cat, level);
                    this.flyToward(this.target);
                    this.cat.getLookControl().setLookAt(this.target);
                    if (this.cat.distanceToSqr(this.target) <= DELIVER_DISTANCE_SQR) {
                        this.cat.setPhase(b.shouldGreet() ? GhostCatPhase.GREET : GhostCatPhase.DELIVER);
                    }
                } else {
                    this.cat.setPhase(GhostCatPhase.DISSIPATE);
                }
            }
            case GREET -> {
                b.onGreetTick(this.cat, level, phaseTicks);
                if (this.target != null) {
                    this.cat.getLookControl().setLookAt(this.target);
                }
                if (phaseTicks >= b.getGreetDuration()) {
                    this.cat.setPhase(GhostCatPhase.DELIVER);
                }
            }
            case DELIVER -> {
                if (b.shouldDeliverGift()) {
                    b.onDeliver(this.cat, level);
                }
                this.cat.setPhase(GhostCatPhase.DISSIPATE);
            }
            case DISSIPATE -> {
                if (phaseTicks == 1) {
                    b.onDissipateStart(this.cat, level);
                }
                // 缓缓上升消散
                this.cat.setDeltaMovement(0.0, 0.04, 0.0);
                if (phaseTicks >= b.getDissipateDuration()) {
                    this.cat.discard();
                }
            }
        }

        // 总寿命耗尽，强制进入消散（不赠礼）
        if (this.cat.getTotalTicks() >= b.getMaxLifetime()
                && this.cat.getPhase() != GhostCatPhase.DISSIPATE) {
            this.cat.setPhase(GhostCatPhase.DISSIPATE);
        }
    }

    // 直线飘行：朝目标头顶上方（y+2.0）插值移动，无视方块碰撞。
    // 目标点抬高到玩家 hitbox 之上，避免猫钻进玩家身体引发视觉穿模与物理贴脸
    private void flyToward(Player target) {
        Vec3 targetPos = target.position().add(0.0, 2.0, 0.0);
        Vec3 dir = targetPos.subtract(this.cat.position());
        double dist = dir.length();
        if (dist < 0.1) {
            return;
        }
        Vec3 norm = dir.normalize();
        // 远距离加速，避免玩家走远后丢失；接近交付距离时减速，减少过冲
        double speed = dist > 64 ? 0.8 : (dist > 32 ? 0.5 : (dist > 4 ? 0.25 : 0.15));
        // 轻微正弦悬停
        double hover = Math.sin(this.cat.getTotalTicks() * 0.15) * 0.02;
        this.cat.setDeltaMovement(norm.x * speed, norm.y * speed + hover, norm.z * speed);
        // 朝向移动方向
        float yaw = (float) Math.toDegrees(Math.atan2(-norm.x, norm.z));
        this.cat.setYRot(yaw);
        this.cat.setYBodyRot(yaw);
    }

    // 解析目标玩家（服务端），无效时返回 null
    private Player resolveTarget() {
        GhostCatBehavior b = this.cat.getBehavior();
        if (b == null) {
            return null;
        }
        UUID uuid = b.getTargetUuid();
        if (uuid == null) {
            return null;
        }
        if (!(this.cat.level() instanceof ServerLevel level)) {
            return null;
        }
        Player player = level.getPlayerByUUID(uuid);
        if (player == null || !player.isAlive()) {
            return null;
        }
        return player;
    }
}
