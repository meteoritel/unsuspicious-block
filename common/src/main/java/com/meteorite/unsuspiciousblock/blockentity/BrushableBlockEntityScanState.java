package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.resources.ResourceLocation;
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

    default boolean unsuspiciousblock$isScanner(UUID uuid) {
        return this.unsuspiciousblock$isScanned()
                && uuid != null
                && uuid.equals(this.unsuspiciousblock$getScannerUuid());
    }
}
