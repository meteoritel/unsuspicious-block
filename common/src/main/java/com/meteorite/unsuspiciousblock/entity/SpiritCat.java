package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.cat.SpiritCatDebugRegistry;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritFlightController;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritMovementState;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritRunMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 猫国灵体公共基类，统一提供预览/职责模式、生命周期、飞行与无碰撞物理。
 */
public abstract class SpiritCat extends Cat {
    private static final String NBT_RUN_MODE = "SpiritRunMode";
    private static final String NBT_LIFETIME = "SpiritLifetime";
    private static final String NBT_LIFETIME_AGE = "SpiritLifetimeAge";
    private static final String NBT_EXPIRES_AT = "SpiritExpiresAt";
    private static final String NBT_SPAWN_Y = "SpiritSpawnY";
    private static final EntityDataAccessor<Integer> DATA_RUN_MODE =
            SynchedEntityData.defineId(SpiritCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MOVEMENT_STATE =
            SynchedEntityData.defineId(SpiritCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(SpiritCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LIFETIME_START_TICK =
            SynchedEntityData.defineId(SpiritCat.class, EntityDataSerializers.INT);

    private final SpiritFlightController flightController = new SpiritFlightController(this);
    private long expiresAtGameTime;
    private double spawnY = Double.NaN;

    protected SpiritCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setCanPickUpLoot(false);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_RUN_MODE, SpiritRunMode.PREVIEW.ordinal());
        builder.define(DATA_MOVEMENT_STATE, SpiritMovementState.HOVER.ordinal());
        builder.define(DATA_LIFETIME, 0);
        builder.define(DATA_LIFETIME_START_TICK, 0);
    }

    // 记录召唤高度，防止穿墙移动时因残余速度沉入世界底部。
    public void freezeSpawnY() {
        this.spawnY = this.getY();
    }

    // 激活正常职责 AI，并设置本次现世最长时间。
    public void activateDuty(int lifetimeTicks) {
        this.activate(SpiritRunMode.DUTY, lifetimeTicks);
    }

    // 激活调试职责 AI，并设置本次现世最长时间。
    public void activateDebugDuty(int lifetimeTicks) {
        this.activate(SpiritRunMode.DEBUG_DUTY, lifetimeTicks);
    }

    // 切回无 AI、无生命周期的模型预览模式。
    public void activatePreview() {
        this.entityData.set(DATA_RUN_MODE, SpiritRunMode.PREVIEW.ordinal());
        this.entityData.set(DATA_LIFETIME, 0);
        this.entityData.set(DATA_LIFETIME_START_TICK, this.tickCount);
        this.expiresAtGameTime = 0L;
        this.flightController.reset();
    }

    // 兼容现有召唤代码；设置寿命时保持当前职责类型，预览实体转为正常职责实体。
    public void setSpiritLifetime(int lifetimeTicks) {
        SpiritRunMode mode = this.getRunMode();
        this.activate(mode.runsDutyAi() ? mode : SpiritRunMode.DUTY, lifetimeTicks);
    }

    // 刷新现有职责实体寿命，不改变正常/调试职责类型。
    public void refreshSpiritLifetime(int lifetimeTicks) {
        SpiritRunMode mode = this.getRunMode();
        this.activate(mode.runsDutyAi() ? mode : SpiritRunMode.DUTY, lifetimeTicks);
    }

    public SpiritRunMode getRunMode() {
        return SpiritRunMode.fromOrdinal(this.entityData.get(DATA_RUN_MODE));
    }

    public boolean isDutyActive() {
        return this.getRunMode().runsDutyAi();
    }

    public boolean isDebugDuty() {
        return this.getRunMode() == SpiritRunMode.DEBUG_DUTY;
    }

    public SpiritMovementState getSpiritMovementState() {
        return SpiritMovementState.fromOrdinal(this.entityData.get(DATA_MOVEMENT_STATE));
    }

    // 更新离散移动状态；相同状态不会产生同步脏数据。
    public void setSpiritMovementState(SpiritMovementState state) {
        if (this.getSpiritMovementState() != state) {
            this.entityData.set(DATA_MOVEMENT_STATE, state.ordinal());
        }
    }

    public SpiritFlightController getFlightController() {
        return this.flightController;
    }

    public int getSpiritLifetime() {
        return this.entityData.get(DATA_LIFETIME);
    }

    public int getSpiritLifetimeAge() {
        int lifetime = this.getSpiritLifetime();
        if (lifetime <= 0) {
            return 0;
        }
        if (this.level().isClientSide) {
            return Math.max(0, this.tickCount - this.entityData.get(DATA_LIFETIME_START_TICK));
        }
        long remaining = Math.max(0L, this.expiresAtGameTime - this.level().getGameTime());
        return (int) Math.min(lifetime, Math.max(0L, lifetime - remaining));
    }

    public int getSpiritLifetimeRemaining() {
        int lifetime = this.getSpiritLifetime();
        return lifetime <= 0 ? 0 : Math.max(0, lifetime - this.getSpiritLifetimeAge());
    }

    // 保存灵体公共运行模式与绝对到期时间。
    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(NBT_RUN_MODE, this.getRunMode().ordinal());
        tag.putInt(NBT_LIFETIME, this.getSpiritLifetime());
        tag.putLong(NBT_EXPIRES_AT, this.expiresAtGameTime);
        if (!Double.isNaN(this.spawnY)) {
            tag.putDouble(NBT_SPAWN_Y, this.spawnY);
        }
    }

    // 恢复新格式数据，并兼容旧的已流逝 tick 格式。
    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        SpiritRunMode mode = SpiritRunMode.fromOrdinal(tag.getInt(NBT_RUN_MODE));
        int lifetime = Math.max(0, tag.getInt(NBT_LIFETIME));
        long expiresAt;
        if (tag.contains(NBT_EXPIRES_AT)) {
            expiresAt = Math.max(0L, tag.getLong(NBT_EXPIRES_AT));
        } else {
            int legacyAge = Math.max(0, tag.getInt(NBT_LIFETIME_AGE));
            expiresAt = this.level().getGameTime() + Math.max(0, lifetime - legacyAge);
            if (lifetime > 0 && mode == SpiritRunMode.PREVIEW) {
                mode = SpiritRunMode.DUTY;
            }
        }
        this.restoreLifecycle(mode, lifetime, expiresAt);
        if (tag.contains(NBT_SPAWN_Y)) {
            this.spawnY = tag.getDouble(NBT_SPAWN_Y);
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.setNoGravity(true);
        if (!this.isDutyActive()) {
            this.flightController.reset();
            return;
        }
        if (!this.level().isClientSide && this.getSpiritLifetime() > 0
                && this.level().getGameTime() >= this.expiresAtGameTime && this.shouldExpireNow()) {
            this.discard();
        }
    }

    // 默认灵体 alpha：显现 10 tick，消散 15 tick；职业实体可覆盖。
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
        if (this.isEffectiveAi() && this.isDutyActive()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
        }
    }

    @Override
    public void move(@NotNull MoverType type, @NotNull Vec3 delta) {
        if (!this.isDutyActive()) {
            return;
        }
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
    public @NotNull InteractionResult mobInteract(@NotNull Player player, @NotNull InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean isFood(@NotNull ItemStack stack) {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected boolean canRide(@NotNull Entity vehicle) {
        return false;
    }

    @Override
    protected boolean canAddPassenger(@NotNull Entity passenger) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public void setCustomName(@Nullable Component name) {
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    // 信使默认不低于召唤高度；需要追击低处目标的子类可关闭该限制。
    protected boolean clampToSpawnHeight() {
        return true;
    }

    // 子类可短暂延迟到期清除，以完成交易关闭或消散演出。
    protected boolean shouldExpireNow() {
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

    @Override
    public void remove(Entity.@NotNull RemovalReason reason) {
        if (!this.level().isClientSide) {
            SpiritCatDebugRegistry.removeEntity(this.getUUID());
        }
        super.remove(reason);
    }

    public static AttributeSupplier.@NotNull Builder createSpiritAttributes() {
        return Cat.createAttributes();
    }

    private void activate(SpiritRunMode mode, int lifetimeTicks) {
        int lifetime = Math.max(0, lifetimeTicks);
        this.entityData.set(DATA_RUN_MODE, mode.ordinal());
        this.entityData.set(DATA_LIFETIME, lifetime);
        this.entityData.set(DATA_LIFETIME_START_TICK, this.tickCount);
        this.expiresAtGameTime = lifetime > 0 ? this.level().getGameTime() + lifetime : 0L;
        this.flightController.reset();
    }

    private void restoreLifecycle(SpiritRunMode mode, int lifetime, long expiresAt) {
        this.entityData.set(DATA_RUN_MODE, mode.ordinal());
        this.entityData.set(DATA_LIFETIME, lifetime);
        this.expiresAtGameTime = expiresAt;
        long remaining = lifetime <= 0 ? 0L : Math.max(0L, expiresAt - this.level().getGameTime());
        int age = lifetime <= 0 ? 0 : (int) Math.min(lifetime, Math.max(0L, lifetime - remaining));
        this.entityData.set(DATA_LIFETIME_START_TICK, this.tickCount - age);
        this.flightController.reset();
    }
}
