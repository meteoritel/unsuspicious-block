package com.meteorite.unsuspiciousblock.core.journal.tracking;

import com.meteorite.unsuspiciousblock.core.loottable.signature.LootCounts;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 容器宿主的追踪状态与原格式 codec。余量清空不终结 pending。 */
public final class ContainerLootTracking {
    private static final String TABLE = "unsuspiciousblock_tracked_loot_table";
    private static final String ITEMS = "unsuspiciousblock_tracked_loot_items";
    private static final String OWNER = "unsuspiciousblock_tracked_player_uuid";
    private final Runnable changed;
    private final PendingJournal pending;
    private final LinkedHashMap<String, Integer> remaining = new LinkedHashMap<>();
    private final Map<String, Integer> view = Collections.unmodifiableMap(remaining);
    private @Nullable ResourceLocation table;
    private @Nullable UUID owner;

    public ContainerLootTracking(Runnable changed) {
        this.changed = changed;
        this.pending = new PendingJournal(changed);
    }

    public @Nullable ResourceLocation table() { return table; }
    public Map<String, Integer> remaining() { return view; }
    public @Nullable UUID owner() { return owner; }
    public PendingJournal pending() { return pending; }

    public void owner(@Nullable UUID next) {
        if (!Objects.equals(owner, next)) {
            owner = next;
            changed.run();
        }
    }

    public void track(ResourceLocation nextTable, Map<String, Integer> counts) {
        Map<String, Integer> normalized = LootCounts.normalize(counts);
        if (normalized.isEmpty()) {
            clearRemaining();
        } else if (!Objects.equals(table, nextTable) || !remaining.equals(normalized)) {
            table = nextTable;
            remaining.clear();
            remaining.putAll(normalized);
            changed.run();
        }
    }

    private void consume(String signature, int amount) {
        int consumed = Math.clamp(amount, 0, remaining.getOrDefault(signature, 0));
        if (consumed > 0) {
            int next = remaining.get(signature) - consumed;
            if (next > 0) remaining.put(signature, next);
            else remaining.remove(signature);
            changed.run();
        }
    }

    public void acquire(ServerPlayer player, ResourceLocation sourceTable, Map<String, Integer> actual,
                        long gameTime, long dayTime) {
        pending.mergeActual(player, sourceTable, actual, gameTime, dayTime);
        actual.forEach(this::consume);
    }

    public void reconcile(Container container) {
        if (table == null || remaining.isEmpty()) return;
        List<LootResultSignature> candidates = new ArrayList<>();
        for (String key : remaining.keySet()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(key);
            if (signature != null) candidates.add(signature);
        }
        if (candidates.isEmpty()) {
            clearRemaining();
            return;
        }
        Map<String, Integer> current = collect(container, candidates);
        LinkedHashMap<String, Integer> reconciled = new LinkedHashMap<>();
        remaining.forEach((key, count) -> {
            int retained = Math.min(count, current.getOrDefault(key, 0));
            if (retained > 0) reconciled.put(key, retained);
        });
        track(table, reconciled);
    }

    public void clearRemaining() {
        if (table != null || !remaining.isEmpty()) {
            table = null;
            remaining.clear();
            changed.run();
        }
    }

    public void clear() {
        clearRemaining();
        pending.discard();
        owner(null);
    }

    public @Nullable ResourceLocation settlementTable() {
        if (table != null) return table;
        var entry = pending.current();
        return entry != null && entry.tableStack() != null && !entry.tableStack().isEmpty()
                ? entry.tableStack().getFirst() : null;
    }

    public void write(CompoundTag tag) {
        if (table == null) tag.remove(TABLE);
        else tag.putString(TABLE, table.toString());
        if (remaining.isEmpty()) tag.remove(ITEMS);
        else tag.put(ITEMS, LootCounts.writeToNbt(remaining));
        if (owner == null) tag.remove(OWNER);
        else tag.putUUID(OWNER, owner);
        pending.write(tag);
    }

    public void read(CompoundTag tag) {
        table = tag.contains(TABLE, Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString(TABLE)) : null;
        pending.read(tag);
        owner = tag.contains(OWNER) ? tag.getUUID(OWNER) : null;
        remaining.clear();
        if (tag.contains(ITEMS, Tag.TAG_COMPOUND)) {
            remaining.putAll(LootCounts.normalize(LootCounts.readFromNbt(tag, ITEMS)));
        }
        if (remaining.isEmpty()) table = null;
    }

    public static Map<String, Integer> collect(Container container, Iterable<LootResultSignature> candidates) {
        List<LootResultSignature> candidateList = new ArrayList<>();
        for (LootResultSignature candidate : candidates) {
            candidateList.add(candidate);
        }

        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            LootResultSignature signature = LootResultMatcher.resolve(
                    stack, candidateList, LootResultPreviewCache.PROVIDER);
            if (signature == null) {
                continue;
            }
            itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
        }
        return LootCounts.normalize(itemCounts);
    }
}
