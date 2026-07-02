package com.meteorite.unsuspiciousblock.entity.ai.lantern;

import com.meteorite.unsuspiciousblock.entity.LanternPet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 提灯宠物空中随机游荡目标。
 * <p>
 * 在实体周围 6~12 格球形范围内随机选点，提灯高度参考当前 Y 上下浮动 3 格，
 * 避免贴地或飞得过高。无目标时不触发；寻路完成或失败后冷却 20~60 tick。
 */
public class LanternPetWanderGoal extends Goal {

    private static final double WANDER_RADIUS = 10.0D;
    private static final int COOLDOWN_MIN = 20;
    private static final int COOLDOWN_MAX = 60;

    private final LanternPet pet;
    private final double speedModifier;
    private int cooldown;

    public LanternPetWanderGoal(LanternPet pet, double speedModifier) {
        this.pet = pet;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (this.pet.getNavigation().isInProgress()) {
            return false;
        }
        return this.pet.getRandom().nextFloat() < 0.05F;
    }

    @Override
    public boolean canContinueToUse() {
        return this.pet.getNavigation().isInProgress();
    }

    @Override
    public void start() {
        Vec3 target = this.pickRandomAirTarget();
        if (target != null) {
            this.pet.getNavigation().moveTo(
                    target.x, target.y, target.z, this.speedModifier);
        }
        this.cooldown = COOLDOWN_MIN
                + this.pet.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN);
    }

    @Override
    public void stop() {
        this.pet.getNavigation().stop();
        this.cooldown = COOLDOWN_MIN;
    }

    // 随机选一个空中目标点
    @Nullable
    private Vec3 pickRandomAirTarget() {
        RandomSource rng = this.pet.getRandom();
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = 4.0D + rng.nextDouble() * WANDER_RADIUS;
        double dx = Math.cos(angle) * dist;
        double dz = Math.sin(angle) * dist;
        double dy = (rng.nextDouble() - 0.5) * 6.0D;
        BlockPos base = this.pet.blockPosition();
        double tx = base.getX() + 0.5 + dx;
        double ty = base.getY() + 0.5 + dy;
        double tz = base.getZ() + 0.5 + dz;
        // 夹到世界高度范围内
        int minY = this.pet.level().getMinBuildHeight() + 2;
        int maxY = this.pet.level().getMaxBuildHeight() - 2;
        ty = Math.max(minY, Math.min(maxY, ty));
        return new Vec3(tx, ty, tz);
    }
}
