package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatBehavior;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatGiftGoal;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatPhase;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritMovementState;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 猫猫信使实体，通过服务端阶段机完成显现、配送、重定位、告别与消散。
 */
public class MessengerCat extends SpiritCat {
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PHASE_START_TICK =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MANIFEST_DUR =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RELOCATE_DUR =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DISSIPATE_DUR =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);

    @Nullable
    private MessengerCatBehavior behavior;
    private MessengerCatPhase phase = MessengerCatPhase.MANIFEST;
    private int phaseTicks;
    private int totalTicks;
    private int relocationCount;
    private boolean deliverySucceeded;

    public MessengerCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, MessengerCatPhase.MANIFEST.ordinal());
        builder.define(DATA_PHASE_START_TICK, 0);
        builder.define(DATA_MANIFEST_DUR, 10);
        builder.define(DATA_RELOCATE_DUR, 20);
        builder.define(DATA_DISSIPATE_DUR, 15);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new MessengerCatGiftGoal(this));
    }

    public void assignBehavior(MessengerCatBehavior behavior) {
        this.behavior = behavior;
        this.entityData.set(DATA_MANIFEST_DUR, Math.max(1, behavior.getManifestDuration()));
        this.entityData.set(DATA_RELOCATE_DUR, Math.max(2, behavior.getRelocateDuration()));
        this.entityData.set(DATA_DISSIPATE_DUR, Math.max(1, behavior.getDissipateDuration()));
    }

    @Nullable
    public MessengerCatBehavior getBehavior() {
        return this.behavior;
    }

    public void beginLifecycle() {
        this.phase = MessengerCatPhase.MANIFEST;
        this.phaseTicks = 0;
        this.totalTicks = 0;
        this.relocationCount = 0;
        this.deliverySucceeded = false;
        this.syncPhaseToClient();
    }

    public void advanceTick() {
        this.phaseTicks++;
        this.totalTicks++;
    }

    public void setPhase(MessengerCatPhase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
        this.setDeltaMovement(Vec3.ZERO);
        this.syncPhaseToClient();
    }

    public MessengerCatPhase getPhase() {
        return this.phase;
    }

    public int getPhaseTicks() {
        return this.phaseTicks;
    }

    public int getTotalTicks() {
        return this.totalTicks;
    }

    public int getRelocationCount() {
        return this.relocationCount;
    }

    public void incrementRelocationCount() {
        this.relocationCount++;
    }

    public boolean isDeliverySucceeded() {
        return this.deliverySucceeded;
    }

    public void setDeliverySucceeded(boolean deliverySucceeded) {
        this.deliverySucceeded = deliverySucceeded;
    }

    private void syncPhaseToClient() {
        this.entityData.set(DATA_PHASE, this.phase.ordinal());
        this.entityData.set(DATA_PHASE_START_TICK, this.tickCount);
    }

    @Override
    public float getRenderAlphaProgress(float partialTick) {
        MessengerCatPhase renderPhase = MessengerCatPhase.values()[Mth.clamp(
                this.entityData.get(DATA_PHASE), 0, MessengerCatPhase.values().length - 1)];
        int startTick = this.entityData.get(DATA_PHASE_START_TICK);
        float elapsed = Math.max(0.0F, this.tickCount - startTick + partialTick);
        float alpha = 1.0F;
        if (renderPhase == MessengerCatPhase.MANIFEST) {
            int duration = Math.max(1, this.entityData.get(DATA_MANIFEST_DUR));
            alpha = Mth.clamp(elapsed / duration, 0.0F, 1.0F);
        } else if (renderPhase == MessengerCatPhase.RELOCATE) {
            int duration = Math.max(2, this.entityData.get(DATA_RELOCATE_DUR));
            float progress = Mth.clamp(elapsed / duration, 0.0F, 1.0F);
            alpha = Math.abs(progress * 2.0F - 1.0F);
        } else if (renderPhase == MessengerCatPhase.DISSIPATE) {
            int duration = Math.max(1, this.entityData.get(DATA_DISSIPATE_DUR));
            alpha = Mth.clamp(1.0F - elapsed / duration, 0.0F, 1.0F);
        }
        return this.getSpiritMovementState() == SpiritMovementState.PHASE ? alpha * 0.55F : alpha;
    }

    public float getAlphaProgress(float partialTick) {
        return this.getRenderAlphaProgress(partialTick);
    }

    @Override
    protected boolean clampToSpawnHeight() {
        return false;
    }

    @Override
    public void remove(Entity.@NotNull RemovalReason reason) {
        if (!this.level().isClientSide && this.behavior != null
                && this.level() instanceof ServerLevel level
                && level.getPlayerByUUID(this.behavior.getTargetUuid()) instanceof ServerPlayer player) {
            CatFavorState state = CatFavorManager.getState(player);
            if (state != null) {
                state.clearActiveMessengerUuid(this.getUUID());
            }
        }
        super.remove(reason);
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes();
    }
}
