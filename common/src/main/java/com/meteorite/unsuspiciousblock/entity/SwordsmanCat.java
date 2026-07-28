package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritMovementState;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SwordsmanCatPhase;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 剑士猫猫实体，通过警戒、追击、蓄势、斩击与脱离状态机短暂保护玩家。
 */
public class SwordsmanCat extends SpiritCat {
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(SwordsmanCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PHASE_START_TICK =
            SynchedEntityData.defineId(SwordsmanCat.class, EntityDataSerializers.INT);
    private static final double TARGET_RANGE = 24.0;
    private static final double RETURN_RANGE = 16.0;
    private static final double REAPPEAR_RANGE = 32.0;
    private static final double ATTACK_START_DISTANCE_SQR = 2.5 * 2.5;
    private static final double STRIKE_HIT_DISTANCE_SQR = 3.25 * 3.25;
    private static final int IDLE_SCAN_TICKS = 20;
    private static final int COMBAT_SCAN_TICKS = 10;
    private static final int ATTACK_INTERVAL_TICKS = 20;
    private static final int MANIFEST_TICKS = 10;
    private static final int WINDUP_TICKS = 4;
    private static final int STRIKE_TICKS = 5;
    private static final int STRIKE_DAMAGE_TICK = 2;
    private static final int RETREAT_TICKS = 8;
    private static final int RETURN_BEFORE_END_TICKS = 40;
    private static final int DISSIPATE_TICKS = 15;
    private static final double FLIGHT_SPEED = 0.5;
    private static final double STRIKE_SPEED = 0.9;

    @Nullable
    private UUID ownerUuid;
    @Nullable
    private UUID preferredTargetUuid;
    @Nullable
    private LivingEntity combatTarget;
    private SwordsmanCatPhase phase = SwordsmanCatPhase.MANIFEST;
    private int phaseTicks;
    private int scanCooldown;
    private int attackCooldown;
    private boolean strikeDamageApplied;
    private double guardSide = 1.0;
    private double attackSide = 1.0;

    public SwordsmanCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, SwordsmanCatPhase.MANIFEST.ordinal());
        builder.define(DATA_PHASE_START_TICK, 0);
    }

    @Override
    protected void registerGoals() {
    }

    @Override
    protected boolean clampToSpawnHeight() {
        return false;
    }

    // 初始化或刷新守护信息；不改变正常职责或调试职责模式。
    public void configureProtection(UUID ownerUuid, @Nullable LivingEntity preferredTarget,
                                    int lifetimeTicks) {
        this.ownerUuid = ownerUuid;
        this.preferredTargetUuid = preferredTarget == null ? null : preferredTarget.getUUID();
        this.combatTarget = null;
        this.scanCooldown = 0;
        this.attackCooldown = 0;
        this.guardSide = this.getRandom().nextBoolean() ? 1.0 : -1.0;
        this.attackSide = this.getRandom().nextBoolean() ? 1.0 : -1.0;
        this.refreshSpiritLifetime(lifetimeTicks);
        this.setPhase(SwordsmanCatPhase.MANIFEST);
    }

    public boolean isProtecting(UUID playerUuid) {
        return playerUuid.equals(this.ownerUuid);
    }

    public SwordsmanCatPhase getSwordsmanPhase() {
        return SwordsmanCatPhase.fromOrdinal(this.entityData.get(DATA_PHASE));
    }

    public int getPhaseTicks() {
        return this.phaseTicks;
    }

    // 调试命令强制切换职业阶段。
    public void forceDebugPhase(SwordsmanCatPhase phase) {
        this.setPhase(phase);
    }

    // 玩家受到新的生物伤害时立即唤醒剑士并提升该攻击者优先级。
    public void notifyOwnerHurt(LivingEntity attacker) {
        this.preferredTargetUuid = attacker.getUUID();
        this.combatTarget = null;
        this.scanCooldown = 0;
        if (this.phase == SwordsmanCatPhase.GUARD) {
            this.setPhase(SwordsmanCatPhase.CHASE);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || this.isRemoved() || !this.isDutyActive()) {
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
        this.phaseTicks++;
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
        }
        if (this.distanceToSqr(owner) > REAPPEAR_RANGE * REAPPEAR_RANGE) {
            this.reappearNearOwner(owner);
        }
        int remaining = this.getSpiritLifetimeRemaining();
        if (remaining > 0 && remaining <= RETURN_BEFORE_END_TICKS
                && this.phase != SwordsmanCatPhase.WINDUP && this.phase != SwordsmanCatPhase.STRIKE
                && this.phase != SwordsmanCatPhase.DISSIPATE) {
            this.combatTarget = null;
            this.setPhase(SwordsmanCatPhase.RETURN);
        }

        switch (this.phase) {
            case MANIFEST -> this.tickManifest(level, owner);
            case GUARD -> this.tickGuard(level, owner);
            case CHASE -> this.tickChase(level, owner);
            case WINDUP -> this.tickWindup(owner);
            case STRIKE -> this.tickStrike(level, owner);
            case RETREAT -> this.tickRetreat(owner);
            case RETURN -> this.tickReturn(level, owner, remaining);
            case DISSIPATE -> this.tickDissipate(level);
        }
    }

    private void tickManifest(ServerLevel level, ServerPlayer owner) {
        this.getFlightController().stop();
        if (this.phaseTicks == 1) {
            level.sendParticles(ParticleTypes.END_ROD,
                    this.getX(), this.getY() + 0.3, this.getZ(), 10, 0.3, 0.25, 0.3, 0.04);
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.CAT_HISS, SoundSource.NEUTRAL, 0.45F, 1.15F);
        }
        LivingEntity preferred = this.resolvePreferredTarget(level);
        this.getLookControl().setLookAt(preferred != null ? preferred : owner);
        if (this.phaseTicks >= MANIFEST_TICKS) {
            this.combatTarget = this.selectTarget(level, owner);
            this.setPhase(this.combatTarget == null ? SwordsmanCatPhase.GUARD : SwordsmanCatPhase.CHASE);
        }
    }

    private void tickGuard(ServerLevel level, ServerPlayer owner) {
        if (this.scanCooldown <= 0) {
            this.combatTarget = this.selectTarget(level, owner);
            this.scanCooldown = IDLE_SCAN_TICKS;
            if (this.combatTarget != null) {
                this.setPhase(SwordsmanCatPhase.CHASE);
                return;
            }
        }
        Vec3 guardPosition = this.getGuardPosition(owner);
        if (this.distanceToSqr(guardPosition) > 1.0) {
            this.getFlightController().moveToward(guardPosition, 0.32, false, 20);
        } else {
            this.getFlightController().stop();
        }
        this.getLookControl().setLookAt(owner);
    }

    private void tickChase(ServerLevel level, ServerPlayer owner) {
        if (this.distanceToSqr(owner) > RETURN_RANGE * RETURN_RANGE) {
            this.setPhase(SwordsmanCatPhase.RETURN);
            return;
        }
        if (this.scanCooldown <= 0 || !this.isValidTarget(this.combatTarget, owner)) {
            this.combatTarget = this.selectTarget(level, owner);
            this.scanCooldown = COMBAT_SCAN_TICKS;
        }
        if (this.combatTarget == null) {
            this.setPhase(SwordsmanCatPhase.GUARD);
            return;
        }
        this.getLookControl().setLookAt(this.combatTarget);
        if (this.distanceToSqr(this.combatTarget) <= ATTACK_START_DISTANCE_SQR && this.attackCooldown <= 0) {
            this.setPhase(SwordsmanCatPhase.WINDUP);
            return;
        }
        Vec3 targetPoint = this.getAttackApproachPosition(this.combatTarget);
        this.getFlightController().moveToward(targetPoint, FLIGHT_SPEED, false, 20);
    }

    private void tickWindup(ServerPlayer owner) {
        if (!this.isValidTarget(this.combatTarget, owner)) {
            this.setPhase(SwordsmanCatPhase.CHASE);
            return;
        }
        this.getFlightController().stop();
        this.getLookControl().setLookAt(this.combatTarget);
        if (this.phaseTicks >= WINDUP_TICKS) {
            this.strikeDamageApplied = false;
            this.setPhase(SwordsmanCatPhase.STRIKE);
        }
    }

    private void tickStrike(ServerLevel level, ServerPlayer owner) {
        if (!this.isValidTarget(this.combatTarget, owner)) {
            this.setPhase(SwordsmanCatPhase.RETREAT);
            return;
        }
        this.getFlightController().moveToward(
                this.combatTarget.getEyePosition(), STRIKE_SPEED, false, 20);
        if (!this.strikeDamageApplied && this.phaseTicks >= STRIKE_DAMAGE_TICK
                && this.distanceToSqr(this.combatTarget) <= STRIKE_HIT_DISTANCE_SQR) {
            this.applyStrike(level, this.combatTarget);
            this.strikeDamageApplied = true;
        }
        if (this.phaseTicks >= STRIKE_TICKS) {
            this.attackCooldown = ATTACK_INTERVAL_TICKS;
            this.setPhase(SwordsmanCatPhase.RETREAT);
        }
    }

    private void tickRetreat(ServerPlayer owner) {
        Vec3 away;
        if (this.combatTarget != null) {
            away = this.position().subtract(this.combatTarget.position());
        } else {
            away = this.position().subtract(owner.position());
        }
        if (away.lengthSqr() < 0.0001) {
            away = new Vec3(this.attackSide, 0.0, 0.0);
        }
        Vec3 retreatPoint = this.position().add(away.normalize().scale(3.0)).add(0.0, 0.8, 0.0);
        this.getFlightController().moveToward(retreatPoint, 0.55, false, 20);
        if (this.phaseTicks >= RETREAT_TICKS) {
            this.setPhase(this.isValidTarget(this.combatTarget, owner)
                    ? SwordsmanCatPhase.CHASE : SwordsmanCatPhase.GUARD);
        }
    }

    private void tickReturn(ServerLevel level, ServerPlayer owner, int remaining) {
        Vec3 guardPosition = this.getGuardPosition(owner);
        if (this.distanceToSqr(guardPosition) > 1.0) {
            this.getFlightController().moveToward(guardPosition, FLIGHT_SPEED, false, 20);
        } else {
            this.getFlightController().stop();
            this.getLookControl().setLookAt(owner);
        }
        if (remaining > 0 && remaining <= DISSIPATE_TICKS) {
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.35F, 0.9F);
            this.setPhase(SwordsmanCatPhase.DISSIPATE);
        } else if (remaining > RETURN_BEFORE_END_TICKS) {
            this.setPhase(SwordsmanCatPhase.GUARD);
        }
    }

    private void tickDissipate(ServerLevel level) {
        if (this.phaseTicks == 1) {
            level.sendParticles(ParticleTypes.END_ROD,
                    this.getX(), this.getY() + 0.3, this.getZ(), 12, 0.35, 0.35, 0.35, 0.08);
        }
        this.setDeltaMovement(0.0, 0.04, 0.0);
    }

    private void applyStrike(ServerLevel level, LivingEntity target) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (target.hurt(this.damageSources().mobAttack(this), damage)) {
            double knockback = this.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            target.knockback(knockback, this.getX() - target.getX(), this.getZ() - target.getZ());
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    2, 0.3, 0.2, 0.3, 0.0);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.55F, 1.25F);
        }
    }

    @Nullable
    private LivingEntity selectTarget(ServerLevel level, ServerPlayer owner) {
        LivingEntity preferred = this.resolvePreferredTarget(level);
        if (this.isValidTarget(preferred, owner)) {
            return preferred;
        }
        LivingEntity lastAttacker = owner.getLastHurtByMob();
        AABB searchBox = owner.getBoundingBox().inflate(TARGET_RANGE);
        List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class, searchBox, candidate -> this.isValidTarget(candidate, owner));
        return candidates.stream()
                .min(Comparator
                        .comparing((LivingEntity candidate) -> candidate != lastAttacker)
                        .thenComparing(candidate -> !this.isTargetingOwner(candidate, owner))
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

    private Vec3 getGuardPosition(ServerPlayer owner) {
        Vec3 look = owner.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 0.0001) {
            horizontal = new Vec3(0.0, 0.0, 1.0);
        }
        horizontal = horizontal.normalize();
        Vec3 side = new Vec3(-horizontal.z, 0.0, horizontal.x).scale(this.guardSide * 1.4);
        return owner.position().add(horizontal.scale(2.2)).add(side).add(0.0, 1.5, 0.0);
    }

    private Vec3 getAttackApproachPosition(LivingEntity target) {
        Vec3 offset = target.position().subtract(this.position());
        Vec3 horizontal = new Vec3(offset.x, 0.0, offset.z);
        Vec3 side = horizontal.lengthSqr() < 0.0001
                ? new Vec3(this.attackSide, 0.0, 0.0)
                : new Vec3(-horizontal.z, 0.0, horizontal.x).normalize().scale(this.attackSide * 1.2);
        return target.getEyePosition().add(side).add(0.0, 0.5, 0.0);
    }

    private void reappearNearOwner(ServerPlayer owner) {
        double angle = this.getRandom().nextDouble() * Math.PI * 2.0;
        double radius = 0.8;
        this.setPos(owner.getX() + Math.cos(angle) * radius,
                owner.getY() + 1.0, owner.getZ() + Math.sin(angle) * radius);
        this.setDeltaMovement(Vec3.ZERO);
        this.setPhase(SwordsmanCatPhase.MANIFEST);
    }

    private void setPhase(SwordsmanCatPhase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
        this.entityData.set(DATA_PHASE, phase.ordinal());
        this.entityData.set(DATA_PHASE_START_TICK, this.tickCount);
        if (phase != SwordsmanCatPhase.STRIKE) {
            this.strikeDamageApplied = false;
        }
    }

    @Override
    public float getRenderAlphaProgress(float partialTick) {
        float alpha = super.getRenderAlphaProgress(partialTick);
        return this.getSpiritMovementState() == SpiritMovementState.PHASE ? alpha * 0.55F : alpha;
    }

    @Override
    public void remove(Entity.@NotNull RemovalReason reason) {
        if (!this.level().isClientSide && this.ownerUuid != null
                && this.level() instanceof ServerLevel level
                && level.getPlayerByUUID(this.ownerUuid) instanceof ServerPlayer owner) {
            CatFavorState state = CatFavorManager.getState(owner);
            if (state != null) {
                state.clearActiveSwordsmanUuid(this.getUUID());
            }
        }
        super.remove(reason);
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes()
                .add(Attributes.MOVEMENT_SPEED, FLIGHT_SPEED)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.4)
                .add(Attributes.FOLLOW_RANGE, TARGET_RANGE);
    }
}
