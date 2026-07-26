package com.meteorite.unsuspiciousblock.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 剑士猫猫——猫之九命触发后短暂保护玩家的无敌猫国灵体。
 */
public class SwordsmanCat extends SpiritCat {
    private static final double TARGET_RANGE = 24.0;
    private static final double ATTACK_DISTANCE_SQR = 4.0;
    private static final int TARGET_REFRESH_TICKS = 10;
    private static final int ATTACK_INTERVAL_TICKS = 20;
    private static final double FLIGHT_SPEED = 0.5;

    @Nullable
    private UUID ownerUuid;
    @Nullable
    private UUID preferredTargetUuid;
    @Nullable
    private LivingEntity combatTarget;
    private int targetRefreshCooldown;
    private int attackCooldown;

    public SwordsmanCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
    }

    @Override
    protected boolean clampToSpawnHeight() {
        return false;
    }

    // 初始化或刷新守护信息；持续时间由召唤方传入。
    public void configureProtection(UUID ownerUuid, @Nullable LivingEntity preferredTarget,
                                    int lifetimeTicks) {
        this.ownerUuid = ownerUuid;
        this.preferredTargetUuid = preferredTarget == null ? null : preferredTarget.getUUID();
        this.combatTarget = null;
        this.targetRefreshCooldown = 0;
        this.refreshSpiritLifetime(lifetimeTicks);
    }

    public boolean isProtecting(UUID playerUuid) {
        return playerUuid.equals(this.ownerUuid);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || this.isRemoved()) {
            return;
        }
        if (!(this.level() instanceof ServerLevel level) || this.ownerUuid == null) {
            this.discard();
            return;
        }
        Player foundOwner = level.getPlayerByUUID(this.ownerUuid);
        if (!(foundOwner instanceof ServerPlayer owner) || !owner.isAlive()) {
            this.discard();
            return;
        }

        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (this.targetRefreshCooldown > 0) {
            this.targetRefreshCooldown--;
        }
        if (!this.isValidTarget(this.combatTarget, owner) || this.targetRefreshCooldown <= 0) {
            this.combatTarget = this.selectTarget(level, owner);
            this.targetRefreshCooldown = TARGET_REFRESH_TICKS;
        }

        if (this.combatTarget != null) {
            this.attackTarget(this.combatTarget);
        } else {
            this.followOwner(owner);
        }
    }

    @Nullable
    private LivingEntity selectTarget(ServerLevel level, ServerPlayer owner) {
        LivingEntity preferred = this.resolvePreferredTarget(level);
        if (this.isValidTarget(preferred, owner)) {
            return preferred;
        }
        AABB searchBox = owner.getBoundingBox().inflate(TARGET_RANGE);
        List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class, searchBox, candidate -> this.isValidTarget(candidate, owner));
        return candidates.stream()
                .min(Comparator
                        .comparing((LivingEntity candidate) -> !this.isTargetingOwner(candidate, owner))
                        .thenComparingDouble(candidate -> candidate.distanceToSqr(owner)))
                .orElse(null);
    }

    @Nullable
    private LivingEntity resolvePreferredTarget(ServerLevel level) {
        if (this.preferredTargetUuid == null) {
            return null;
        }
        Entity entity = level.getEntity(this.preferredTargetUuid);
        return entity instanceof LivingEntity living ? living : null;
    }

    private boolean isValidTarget(@Nullable LivingEntity candidate, ServerPlayer owner) {
        if (candidate == null || !candidate.isAlive() || candidate == this || candidate == owner) {
            return false;
        }
        if (candidate instanceof Player || candidate instanceof Villager || candidate instanceof Cat) {
            return false;
        }
        if (candidate instanceof TamableAnimal tamable && tamable.isTame()) {
            return false;
        }
        if (candidate instanceof NeutralMob) {
            return false;
        }
        return candidate instanceof Enemy && candidate.distanceToSqr(owner) <= TARGET_RANGE * TARGET_RANGE;
    }

    private boolean isTargetingOwner(LivingEntity candidate, ServerPlayer owner) {
        return candidate instanceof Mob mob && mob.getTarget() == owner;
    }

    private void attackTarget(LivingEntity target) {
        Vec3 offset = target.getEyePosition().subtract(this.position());
        double distance = offset.length();
        if (distance > 0.001) {
            Vec3 direction = offset.scale(1.0 / distance);
            if (this.distanceToSqr(target) > ATTACK_DISTANCE_SQR) {
                this.setDeltaMovement(direction.scale(FLIGHT_SPEED));
            } else {
                this.setDeltaMovement(Vec3.ZERO);
                this.tryDamageTarget(target);
            }
            this.faceDirection(direction);
        }
    }

    private void tryDamageTarget(LivingEntity target) {
        if (this.attackCooldown > 0) {
            return;
        }
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (target.hurt(this.damageSources().mobAttack(this), damage)) {
            double knockback = this.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            target.knockback(knockback, this.getX() - target.getX(), this.getZ() - target.getZ());
        }
        this.attackCooldown = ATTACK_INTERVAL_TICKS;
    }

    private void followOwner(ServerPlayer owner) {
        Vec3 offset = owner.position().add(0.0, 1.5, 0.0).subtract(this.position());
        if (offset.lengthSqr() <= 16.0) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 direction = offset.normalize();
        this.setDeltaMovement(direction.scale(FLIGHT_SPEED));
        this.faceDirection(direction);
    }

    private void faceDirection(Vec3 direction) {
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes()
                .add(Attributes.MOVEMENT_SPEED, FLIGHT_SPEED)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.4)
                .add(Attributes.FOLLOW_RANGE, TARGET_RANGE);
    }
}
