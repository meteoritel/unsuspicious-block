package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import noobanidus.mods.lootr.common.data.LootrInventory;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 为 Lootr 的每玩家库存附加运行时战利品追踪状态。
 * <p>
 * Lootr 打开菜单时以 {@link LootrInventory} 为容器，而非原版 BlockEntity。
 * 本 Mixin 使其实现 {@link TrackedContainerLootState}，让菜单核对阶段能自然找到追踪状态。
 * <p>
 * 追踪状态为运行时瞬态：不持久化到 LootrSavedData（writeTrackedLootData/readTrackedLootData 为空操作）。
 */
@Mixin(LootrInventory.class)
public abstract class LootrInventoryMixin implements TrackedContainerLootState {

    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$trackedLootTableName;

    @Unique
    private final LinkedHashMap<String, Integer> unsuspiciousblock$trackedLootCounts = new LinkedHashMap<>();

    @Unique
    @Nullable
    private ExcavationLogEntry unsuspiciousblock$pendingJournalEntry;

    @Unique
    @Nullable
    private UUID unsuspiciousblock$trackedPlayerUuid;

    // 返回当前容器关联的已追踪战利品表
    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getTrackedLootTableName() {
        return this.unsuspiciousblock$trackedLootTableName;
    }

    // 返回当前容器中尚未结算到玩家背包的追踪物品数量
    @Override
    public Map<String, Integer> unsuspiciousblock$getTrackedLootCounts() {
        return this.unsuspiciousblock$trackedLootCounts;
    }

    @Override
    @Nullable
    public ExcavationLogEntry unsuspiciousblock$getPendingJournalEntry() {
        return this.unsuspiciousblock$pendingJournalEntry;
    }

    @Override
    public void unsuspiciousblock$setPendingJournalEntry(@Nullable ExcavationLogEntry entry) {
        if (Objects.equals(this.unsuspiciousblock$pendingJournalEntry, entry)) {
            return;
        }
        this.unsuspiciousblock$pendingJournalEntry = entry;
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 返回触发战利品表解析的追踪玩家 UUID
    @Override
    @Nullable
    public UUID unsuspiciousblock$getTrackedPlayerUuid() {
        return this.unsuspiciousblock$trackedPlayerUuid;
    }

    // 设置触发战利品表解析的追踪玩家 UUID
    @Override
    public void unsuspiciousblock$setTrackedPlayerUuid(@Nullable UUID uuid) {
        if (Objects.equals(this.unsuspiciousblock$trackedPlayerUuid, uuid)) {
            return;
        }
        this.unsuspiciousblock$trackedPlayerUuid = uuid;
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 用本次开箱解析结果覆盖容器追踪状态
    @Override
    public void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<String, Integer> itemCounts) {
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>(LootCounts.normalize(itemCounts));
        if (normalized.isEmpty()) {
            this.unsuspiciousblock$clearTrackedLoot();
            return;
        }
        if (Objects.equals(this.unsuspiciousblock$trackedLootTableName, tableId)
                && this.unsuspiciousblock$trackedLootCounts.equals(normalized)) {
            return;
        }

        this.unsuspiciousblock$trackedLootTableName = tableId;
        this.unsuspiciousblock$trackedLootCounts.clear();
        this.unsuspiciousblock$trackedLootCounts.putAll(normalized);
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 消耗指定物品签名的已追踪数量，并返回本次实际结算的数量
    @Override
    public int unsuspiciousblock$consumeTrackedLoot(String signatureKey, int amount) {
        if (amount <= 0) {
            return 0;
        }

        Integer current = this.unsuspiciousblock$trackedLootCounts.get(signatureKey);
        if (current == null || current <= 0) {
            return 0;
        }

        int consumed = Math.min(current, amount);
        int remaining = current - consumed;
        if (remaining > 0) {
            this.unsuspiciousblock$trackedLootCounts.put(signatureKey, remaining);
        } else {
            this.unsuspiciousblock$trackedLootCounts.remove(signatureKey);
        }
        this.unsuspiciousblock$markTrackingChanged();
        return consumed;
    }

    // 清空当前容器保存的战利品追踪状态（保留 pendingJournalEntry，由调用方在结算完成后显式清除）
    @Override
    public void unsuspiciousblock$clearTrackedLoot() {
        if (this.unsuspiciousblock$trackedLootTableName == null && this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            return;
        }

        this.unsuspiciousblock$trackedLootTableName = null;
        this.unsuspiciousblock$trackedLootCounts.clear();
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 标记追踪状态已变化--LootrInventory 非 BlockEntity，追踪状态为瞬态，无需触发持久化
    @Unique
    private void unsuspiciousblock$markTrackingChanged() {
        // no-op：追踪状态不持久化到 LootrSavedData
    }

    // 追踪状态不持久化（瞬态），写入为空操作
    @Override
    public void unsuspiciousblock$writeTrackedLootData(CompoundTag tag) {
        // no-op
    }

    // 追踪状态不持久化（瞬态），读取为空操作
    @Override
    public void unsuspiciousblock$readTrackedLootData(CompoundTag tag) {
        // no-op
    }
}
