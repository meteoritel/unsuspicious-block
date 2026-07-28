package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatBehavior;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatGiftGoal;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatPhase;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 猫猫信使实体——通过阶段机完成晨礼配送。
 */
public class MessengerCat extends SpiritCat {
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PHASE_START_TICK =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MANIFEST_DUR =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DISSIPATE_DUR =
            SynchedEntityData.defineId(MessengerCat.class, EntityDataSerializers.INT);

    @Nullable
    private MessengerCatBehavior behavior;
    private MessengerCatPhase phase = MessengerCatPhase.MANIFEST;
    private int phaseTicks;
    private int totalTicks;

    public MessengerCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, MessengerCatPhase.MANIFEST.ordinal());
        builder.define(DATA_PHASE_START_TICK, 0);
        builder.define(DATA_MANIFEST_DUR, 10);
        builder.define(DATA_DISSIPATE_DUR, 15);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new MessengerCatGiftGoal(this));
    }

    public void assignBehavior(MessengerCatBehavior behavior) {
        this.behavior = behavior;
        this.entityData.set(DATA_MANIFEST_DUR, Math.max(1, behavior.getManifestDuration()));
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

    private void syncPhaseToClient() {
        this.entityData.set(DATA_PHASE, this.phase.ordinal());
        this.entityData.set(DATA_PHASE_START_TICK, this.tickCount);
    }

    @Override
    public float getRenderAlphaProgress(float partialTick) {
        int phaseOrdinal = this.entityData.get(DATA_PHASE);
        MessengerCatPhase renderPhase;
        try {
            renderPhase = MessengerCatPhase.values()[phaseOrdinal];
        } catch (ArrayIndexOutOfBoundsException ex) {
            return 1.0F;
        }
        if (renderPhase != MessengerCatPhase.MANIFEST && renderPhase != MessengerCatPhase.DISSIPATE) {
            return 1.0F;
        }
        int startTick = this.entityData.get(DATA_PHASE_START_TICK);
        float elapsed = Math.max(0.0F, this.tickCount - startTick + partialTick);
        int duration = renderPhase == MessengerCatPhase.MANIFEST
                ? Math.max(1, this.entityData.get(DATA_MANIFEST_DUR))
                : Math.max(1, this.entityData.get(DATA_DISSIPATE_DUR));
        return renderPhase == MessengerCatPhase.MANIFEST
                ? Mth.clamp(elapsed / duration, 0.0F, 1.0F)
                : Mth.clamp(1.0F - elapsed / duration, 0.0F, 1.0F);
    }

    // 兼容现有渲染器与调试代码。
    public float getAlphaProgress(float partialTick) {
        return this.getRenderAlphaProgress(partialTick);
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes();
    }
}
