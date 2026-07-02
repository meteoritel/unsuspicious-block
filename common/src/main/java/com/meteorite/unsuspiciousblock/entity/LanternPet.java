package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.entity.ai.lantern.LanternPetFollowSoulCarrierGoal;
import com.meteorite.unsuspiciousblock.entity.ai.lantern.LanternPetWanderGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * 灵魂提灯宠物 —— 以原版灵魂灯笼为外形的飞行宠物。
 * <p>
 * 无重力飘行，会随机在空中游荡；当附近玩家手持灵魂类物品
 * （灵魂沙/灵魂土/灵魂灯笼/灵魂火把）时会被吸引并跟随。
 * 持续散发灵魂火苗与蓝色灵魂粒子，营造幽冥灯笼氛围。
 * 不可推动、不可碰撞、不可伤害，仅作陪伴与氛围用途。
 */
public class LanternPet extends PathfinderMob {

    // 粒子触发相关阈值（tick 周期）
    private static final int SOUL_FLAME_INTERVAL = 3;
    private static final int SOUL_EMBER_INTERVAL = 20;
    // 浮动相位速度
    private static final float BOB_SPEED = 0.15F;
    private static final float BOB_AMPLITUDE = 0.06F;

    public LanternPet(EntityType<? extends LanternPet> type, Level level) {
        super(type, level);
        // 召唤宠物，不参与自然刷新清理
        this.setPersistenceRequired();
        this.setNoGravity(true);
        // 视为可飞行路径类型，避免贴地寻路
        this.setPathfindingMalus(PathType.DANGER_FIRE, -1.0F);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.LAVA, -1.0F);
        this.moveControl = new FlyingMoveControl(this, 20, true);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LanternPetFollowSoulCarrierGoal(this, 16.0D, 1.6D));
        this.goalSelector.addGoal(2, new LanternPetWanderGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
    }

    @Override
    protected @NotNull PathNavigation createNavigation(@NotNull Level level) {
        // 飞行寻路：可在空中无障碍移动
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        nav.setCanPassDoors(true);
        return nav;
    }

    // ========== 物理：飞行 + 浮动 ==========

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    public void travel(@NotNull Vec3 travelVector) {
        if (this.isEffectiveAi()) {
            // 飞行位移：直接由 moveControl + deltaMovement 驱动
            this.move(MoverType.SELF, this.getDeltaMovement());
        }
        // 不调用 super.travel，跳过原版重力/摩擦/惯性
    }

    // aiStep 钩子：服务端每 tick 推进粒子与浮动效果
    @Override
    public void aiStep() {
        super.aiStep();
        // 上下浮动微调：在 y 方向施加正弦轻微速度，让悬停不死板
        if (this.isEffectiveAi() && this.getDeltaMovement().y < 0.05F) {
            float phase = (this.tickCount + this.getId()) * BOB_SPEED;
            this.setDeltaMovement(
                    this.getDeltaMovement().x,
                    this.getDeltaMovement().y + Mth.sin(phase) * BOB_AMPLITUDE * 0.1F,
                    this.getDeltaMovement().z);
        }
        this.spawnAmbientParticles();
    }

    // 持续散发灵魂火苗、偶发灵魂粒子，移动时拖尾烟雾
    private void spawnAmbientParticles() {
        if (this.level().isClientSide) {
            // 客户端也参与绘制，避免完全依赖服务端广播
            RandomSource rng = this.getRandom();
            if (this.tickCount % SOUL_FLAME_INTERVAL == 0) {
                this.level().addParticle(ParticleTypes.SOUL_FIRE_FLAME,
                        this.getX() + (rng.nextDouble() - 0.5) * 0.3,
                        this.getY() + 0.2,
                        this.getZ() + (rng.nextDouble() - 0.5) * 0.3,
                        (rng.nextDouble() - 0.5) * 0.02,
                        0.04 + rng.nextDouble() * 0.02,
                        (rng.nextDouble() - 0.5) * 0.02);
            }
            if (this.tickCount % SOUL_EMBER_INTERVAL == 0) {
                this.level().addParticle(ParticleTypes.SOUL,
                        this.getX() + (rng.nextDouble() - 0.5) * 0.4,
                        this.getY() + 0.3,
                        this.getZ() + (rng.nextDouble() - 0.5) * 0.4,
                        (rng.nextDouble() - 0.5) * 0.03,
                        0.02,
                        (rng.nextDouble() - 0.5) * 0.03);
            }
            // 移动时拖尾烟雾
            if (this.getDeltaMovement().horizontalDistanceSqr() > 0.002) {
                this.level().addParticle(ParticleTypes.SMOKE,
                        this.getX(), this.getY() + 0.15, this.getZ(),
                        0.0, 0.01, 0.0);
            }
        } else if (this.level() instanceof ServerLevel serverLevel) {
            // 服务端偶发播放灵魂灯笼环境音
            if (this.tickCount % 200 == 0 && this.getRandom().nextInt(3) == 0) {
                serverLevel.playSound(null, this.blockPosition(),
                        SoundEvents.SOUL_ESCAPE.value(), SoundSource.NEUTRAL,
                        0.3F, 1.2F + this.getRandom().nextFloat() * 0.3F);
            }
        }
    }

    // ========== 不可交互边界 ==========

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canCollideWith(@NotNull Entity other) {
        return false;
    }

    @Override
    public void push(@NotNull Entity entity) {
    }

    @Override
    public void pushEntities() {
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    // ========== 生成与读取限制 ==========

    @Override
    public float getWalkTargetValue(@NotNull BlockPos pos, @NotNull LevelReader level) {
        // 飞行宠物，高度自由；中性评价值即可
        return 0.5F;
    }

    // ========== 属性 ==========

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 4.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FLYING_SPEED, 0.6D);
    }
}
