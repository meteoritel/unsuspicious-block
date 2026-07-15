package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface BrushableBlockEntityScanState {
    void unsuspiciousblock$markScanned(UUID scannerUuid);

    void unsuspiciousblock$clearScanned();

    boolean unsuspiciousblock$isScanned();

    @Nullable
    UUID unsuspiciousblock$getScannerUuid();

    @Nullable
    ResourceLocation unsuspiciousblock$getLootTableName();

    boolean unsuspiciousblock$isLootTableParsed();

    // 获取可疑方块内的物品
    ItemStack unsuspiciousblock$getItem();

    // 设置可疑方块内的物品
    void unsuspiciousblock$setItem(ItemStack stack);

    @Nullable
    ExcavationLogEntry unsuspiciousblock$getPendingJournalEntry();

    void unsuspiciousblock$setPendingJournalEntry(@Nullable ExcavationLogEntry entry);

    default void unsuspiciousblock$clearPendingJournalEntry() {
        this.unsuspiciousblock$setPendingJournalEntry(null);
    }

    // 解析战利品表并返回生成的物品副本，由各平台实现覆盖
    default ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        return ItemStack.EMPTY;
    }

    // 标记底层方块实体已变更并同步到客户端，由各平台实现覆盖
    default void unsuspiciousblock$markBlockEntityChanged() {
        // no-op
    }

    default boolean unsuspiciousblock$isScanner(UUID uuid) {
        return this.unsuspiciousblock$isScanned()
                && uuid != null
                && uuid.equals(this.unsuspiciousblock$getScannerUuid());
    }
}
