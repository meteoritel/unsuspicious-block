package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/** 宿主内的待定日志；提交成功后才替换或清除条目。 */
public final class PendingJournal {
    private static final String TAG = "unsuspiciousblock_pending_journal_entry";
    private final Runnable changed;
    private @Nullable ExcavationLogEntry entry;

    public PendingJournal(Runnable changed) {
        this.changed = changed;
    }

    public @Nullable ExcavationLogEntry current() {
        return entry;
    }

    public void stage(ExcavationLogEntry next) {
        replace(Objects.requireNonNull(next));
    }

    public void mergeActual(ServerPlayer player, ResourceLocation table, Map<String, Integer> actual,
                            long gameTime, long dayTime) {
        replace(ArchaeologyLootRuntimeTracker.applyPendingLoot(player, table, entry, actual, gameTime, dayTime));
    }

    public void mergeActual(ServerPlayer player, ResourceLocation table, ItemStack actual,
                            long gameTime, long dayTime) {
        replace(ArchaeologyLootRuntimeTracker.applyPendingLoot(player, table, entry, actual, gameTime, dayTime));
    }

    public void finish(ServerPlayer player, ResourceLocation table) {
        ArchaeologyLootRuntimeTracker.finalizePendingEntry(player, table, entry);
        discard();
    }

    public void discard() {
        replace(null);
    }

    private void replace(@Nullable ExcavationLogEntry next) {
        if (!Objects.equals(entry, next)) {
            entry = next;
            changed.run();
        }
    }

    public void write(CompoundTag tag) {
        if (entry == null) {
            tag.remove(TAG);
        } else {
            tag.put(TAG, entry.toTag());
        }
    }

    public void read(CompoundTag tag) {
        entry = tag.contains(TAG, Tag.TAG_COMPOUND) ? ExcavationLogEntry.fromTag(tag.getCompound(TAG)) : null;
    }
}
