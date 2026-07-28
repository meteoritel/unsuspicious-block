package com.meteorite.unsuspiciousblock.cat.merchant;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 猫猫商人每世界生成状态——持久化下次尝试时间、渐进概率档位与活动实体 UUID。
 */
public final class MerchantCatSpawnData extends SavedData {
    private static final String FILE_NAME = "unsuspiciousblock_merchant_cat";
    private static final String NEXT_ATTEMPT_TAG = "next_attempt";
    private static final String FAILURE_STEP_TAG = "failure_step";
    private static final String ACTIVE_MERCHANT_TAG = "active_merchant";
    private static final SavedData.Factory<MerchantCatSpawnData> FACTORY = new SavedData.Factory<>(
            MerchantCatSpawnData::new,
            MerchantCatSpawnData::load,
            DataFixTypes.LEVEL);

    private long nextAttempt;
    private int failureStep;
    @Nullable
    private UUID activeMerchantUuid;

    private MerchantCatSpawnData() {
    }

    public static MerchantCatSpawnData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static MerchantCatSpawnData load(CompoundTag tag, HolderLookup.Provider registries) {
        MerchantCatSpawnData data = new MerchantCatSpawnData();
        data.nextAttempt = tag.getLong(NEXT_ATTEMPT_TAG);
        data.failureStep = Math.max(0, Math.min(2, tag.getInt(FAILURE_STEP_TAG)));
        if (tag.hasUUID(ACTIVE_MERCHANT_TAG)) {
            data.activeMerchantUuid = tag.getUUID(ACTIVE_MERCHANT_TAG);
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag,
                                     HolderLookup.@NotNull Provider registries) {
        tag.putLong(NEXT_ATTEMPT_TAG, this.nextAttempt);
        tag.putInt(FAILURE_STEP_TAG, this.failureStep);
        if (this.activeMerchantUuid != null) {
            tag.putUUID(ACTIVE_MERCHANT_TAG, this.activeMerchantUuid);
        }
        return tag;
    }

    public long getNextAttempt() {
        return this.nextAttempt;
    }

    public void scheduleNext(long gameTime, int intervalTicks) {
        this.nextAttempt = gameTime + intervalTicks;
        this.setDirty();
    }

    public float getChance() {
        return switch (this.failureStep) {
            case 0 -> 0.25F;
            case 1 -> 0.50F;
            default -> 0.75F;
        };
    }

    public void recordFailure() {
        this.failureStep = Math.min(2, this.failureStep + 1);
        this.setDirty();
    }

    public void recordSuccess(UUID merchantUuid) {
        this.failureStep = 0;
        this.activeMerchantUuid = merchantUuid;
        this.setDirty();
    }

    public @Nullable UUID getActiveMerchantUuid() {
        return this.activeMerchantUuid;
    }

    public void clearActive(UUID merchantUuid) {
        if (merchantUuid.equals(this.activeMerchantUuid)) {
            this.activeMerchantUuid = null;
            this.setDirty();
        }
    }
}
