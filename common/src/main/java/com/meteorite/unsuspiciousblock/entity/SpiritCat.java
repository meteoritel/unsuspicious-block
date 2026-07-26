package com.meteorite.unsuspiciousblock.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

/**
 * 猫国灵体公共基类——提供无碰撞、无敌、穿墙飘行与可传入的临时寿命。
 */
public abstract class SpiritCat extends Cat {
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(SpiritCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LIFETIME_AGE =
            SynchedEntityData.defineId(SpiritCat.class, EntityDataSerializers.INT);

    private double spawnY = Double.NaN;

    protected SpiritCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setCanPickUpLoot(false);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LIFETIME, 0);
        builder.define(DATA_LIFETIME_AGE, 0);
    }

    // 记录召唤高度，防止穿墙移动时因残余速度沉入世界底部。
    public void freezeSpawnY() {
        this.spawnY = this.getY();
    }

    // 设置由召唤方传入的寿命；小于等于 0 表示由子类自行管理。
    public void setSpiritLifetime(int lifetimeTicks) {
        this.entityData.set(DATA_LIFETIME, Math.max(0, lifetimeTicks));
        this.entityData.set(DATA_LIFETIME_AGE, 0);
    }

    // 刷新寿命并从头计时。
    public void refreshSpiritLifetime(int lifetimeTicks) {
        this.setSpiritLifetime(lifetimeTicks);
    }

    public int getSpiritLifetime() {
        return this.entityData.get(DATA_LIFETIME);
    }

    public int getSpiritLifetimeAge() {
        return this.entityData.get(DATA_LIFETIME_AGE);
    }

    // 恢复已保存的寿命进度，仅供需要跨区块持久化的灵体子类使用。
    protected void restoreSpiritLifetime(int lifetimeTicks, int lifetimeAge) {
        this.entityData.set(DATA_LIFETIME, Math.max(0, lifetimeTicks));
        this.entityData.set(DATA_LIFETIME_AGE, Math.max(0, lifetimeAge));
    }

    // 保存灵体公共寿命数据；默认不保存的子类不会进入该流程。
    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SpiritLifetime", this.getSpiritLifetime());
        tag.putInt("SpiritLifetimeAge", this.getSpiritLifetimeAge());
        if (!Double.isNaN(this.spawnY)) {
            tag.putDouble("SpiritSpawnY", this.spawnY);
        }
    }

    // 恢复灵体公共寿命数据。
    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.restoreSpiritLifetime(tag.getInt("SpiritLifetime"), tag.getInt("SpiritLifetimeAge"));
        if (tag.contains("SpiritSpawnY")) {
            this.spawnY = tag.getDouble("SpiritSpawnY");
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            int lifetime = this.getSpiritLifetime();
            if (lifetime > 0) {
                int age = this.getSpiritLifetimeAge() + 1;
                this.entityData.set(DATA_LIFETIME_AGE, age);
                if (age >= lifetime) {
                    this.discard();
                }
            }
        }
    }

    // 默认灵体 alpha：显现 10 tick，消散 15 tick；子类可覆盖。
    public float getRenderAlphaProgress(float partialTick) {
        int lifetime = this.getSpiritLifetime();
        if (lifetime <= 0) {
            return 1.0F;
        }
        float age = this.getSpiritLifetimeAge() + partialTick;
        if (age < 10.0F) {
            return Mth.clamp(age / 10.0F, 0.0F, 1.0F);
        }
        float remaining = lifetime - age;
        if (remaining < 15.0F) {
            return Mth.clamp(remaining / 15.0F, 0.0F, 1.0F);
        }
        return 1.0F;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    public void travel(@NotNull Vec3 travelVector) {
        if (this.isEffectiveAi()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
        }
    }

    @Override
    public void move(@NotNull MoverType type, @NotNull Vec3 delta) {
        double newY = this.getY() + delta.y;
        double minimumY = this.level().getMinBuildHeight() + 1.0;
        if (this.clampToSpawnHeight() && !Double.isNaN(this.spawnY) && this.spawnY > minimumY) {
            minimumY = this.spawnY;
        }
        this.setPos(this.getX() + delta.x, Math.max(newY, minimumY), this.getZ() + delta.z);
    }

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

    // 信使默认不低于召唤高度；需要追击低处目标的子类可关闭该限制。
    protected boolean clampToSpawnHeight() {
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public static AttributeSupplier.@NotNull Builder createSpiritAttributes() {
        return Cat.createAttributes();
    }
}
