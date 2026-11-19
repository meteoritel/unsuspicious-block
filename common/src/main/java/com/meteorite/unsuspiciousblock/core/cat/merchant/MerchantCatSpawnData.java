package com.meteorite.unsuspiciousblock.core.cat.merchant;

import com.meteorite.unsuspiciousblock.core.entity.MerchantCat;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 猫猫商人每世界生成状态——持久化生成调度和维度内商人的占位期限。
 */
public final class MerchantCatSpawnData extends SavedData {
    private static final String FILE_NAME = "unsuspiciousblock_merchant_cat";
    private static final String NEXT_ATTEMPT_TAG = "next_attempt";
    private static final String FAILURE_STEP_TAG = "failure_step";
    private static final String ACTIVE_MERCHANT_TAG = "active_merchant";
    private static final String ACTIVE_UNTIL_TAG = "active_until";
    private static final SavedData.Factory<MerchantCatSpawnData> FACTORY = new SavedData.Factory<>(
            MerchantCatSpawnData::new,
            MerchantCatSpawnData::load,
            DataFixTypes.LEVEL);

    private long nextAttempt;
    private int failureStep;
    private @Nullable UUID activeMerchant;
    private long activeUntil;

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
            data.activeMerchant = tag.getUUID(ACTIVE_MERCHANT_TAG);
            data.activeUntil = tag.getLong(ACTIVE_UNTIL_TAG);
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag,
                                     HolderLookup.@NotNull Provider registries) {
        tag.putLong(NEXT_ATTEMPT_TAG, this.nextAttempt);
        tag.putInt(FAILURE_STEP_TAG, this.failureStep);
        if (this.activeMerchant != null) {
            tag.putUUID(ACTIVE_MERCHANT_TAG, this.activeMerchant);
            tag.putLong(ACTIVE_UNTIL_TAG, this.activeUntil);
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

    /** 只有成功添加到世界的自然商人才占据维度名额。 */
    public void recordSuccess(MerchantCat merchant) {
        this.failureStep = 0;
        this.activeMerchant = merchant.getUUID();
        this.activeUntil = merchant.getSpiritExpiryGameTime();
        this.setDirty();
    }

    /** 判断当前维度是否已有商人占位，释放在卸载期间到期的占位。 */
    public boolean hasActiveMerchant(ServerLevel level) {
        UUID uuid = this.activeMerchant;
        if (uuid == null) {
            return false;
        }
        Entity entity = level.getEntity(uuid);
        if (entity instanceof MerchantCat merchant && merchant.isAlive()
                && merchant.isDutyActive() && !merchant.isDebugDuty()) {
            setDeadline(merchant.getSpiritExpiryGameTime());
            return true;
        }
        if (entity == null) {
            long now = level.getGameTime();
            // 旧档只记录 UUID，最多保留一次当前配置的商人寿命等待实体加载。
            if (this.activeUntil == 0) {
                setDeadline(now + Services.SPIRIT_CAT_CONFIG.getMerchantLifetimeTicks());
            }
            if (now < this.activeUntil) {
                return true;
            }
        }
        clearActive(uuid);
        return false;
    }

    private void setDeadline(long expiresAt) {
        if (this.activeUntil != expiresAt) {
            this.activeUntil = expiresAt;
            this.setDirty();
        }
    }

    /** 判断该实体是否仍拥有维度名额。 */
    public boolean isActive(UUID merchantUuid) {
        return merchantUuid.equals(this.activeMerchant);
    }

    /** 实体终止只释放自己的名额，避免延迟加载的旧实体影响新商人。 */
    public void clearActive(UUID merchantUuid) {
        if (isActive(merchantUuid)) {
            this.activeMerchant = null;
            this.activeUntil = 0;
            this.setDirty();
        }
    }
}
