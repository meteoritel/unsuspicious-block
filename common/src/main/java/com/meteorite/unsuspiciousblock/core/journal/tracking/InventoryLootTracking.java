package com.meteorite.unsuspiciousblock.core.journal.tracking;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry.GameTimestamp;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.MergeAcquisition;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootCounts;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalStateHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将来源及余量附在实际库存上，个人取得记录由考古笔记持有。 */
public final class InventoryLootTracking {
    private static final String GENERATION = "unsuspiciousblock_inventory_generation";
    private static final String DISCOVERIES = "unsuspiciousblock_inventory_discoveries";

    private final Runnable changed;
    private final ContainerLootTracking contents;
    private @Nullable UUID generation;
    private Map<ResourceLocation, Map<String, Integer>> discoveries = Map.of();

    public InventoryLootTracking(Runnable changed) {
        this.changed = changed;
        this.contents = new ContainerLootTracking(changed);
    }

    /** 库存来源可在新版扫描授权及正常世界重载之间比较。 */
    public @Nullable UUID generation() {
        return generation;
    }

    /** 扫描未收录的战利品也需要识别其原始库存。 */
    public UUID bindGeneration() {
        if (generation == null) {
            generation = UUID.randomUUID();
            changed.run();
        }
        return generation;
    }

    /** 迁入旧扫描的来源时，调用方已核对扫描者、根表和实际槽位。 */
    public void adoptScannedSource(ResourceLocation table, ExcavationLogEntry source) {
        contents.pending().stage(source);
        contents.track(table, source.expectedLoot());
        generation = source.entryId();
        changed.run();
    }

    /** 旧方块待定日志仅在库存本身还没有来源时才可迁入。 */
    public boolean hasSource() {
        return contents.pending().current() != null;
    }

    /** 保留填入库存的根表物品，以及已按最终库存校正的子表结果。 */
    public void stage(LootSession.Commit commit, ExcavationLogEntry source) {
        contents.pending().stage(source);
        contents.track(commit.rootContext().rootTableId(), commit.finalItemCounts());
        discoveries = commit.discoveredLoot();
        generation = source.entryId();
        changed.run();
    }

    public Map<String, Integer> remaining() {
        return contents.remaining();
    }

    /** 菜单校准只缩减余量。过期后仍保留扫描识别所需的库存 ID。 */
    public void reconcileMenu(Container inventory, long gameTime) {
        ExcavationLogEntry source = contents.pending().current();
        if (source == null) return;
        long timeout = ServerLootTableConfigManager.effectiveSettings().timeoutTicks();
        if (gameTime - source.createdGameTime() > timeout) {
            contents.clear();
        } else {
            contents.reconcile(inventory);
        }
        if (remaining().isEmpty() && !discoveries.isEmpty()) {
            discoveries = Map.of();
            changed.run();
        }
    }

    /** 刷拭或铲取消耗原始槽位，精掘、拆栈及实际交付决定取得量。 */
    public void acquireBrushLoot(ServerPlayer player, ItemStack original, int deliveredCount) {
        ResourceLocation rootTable = contents.table();
        ExcavationLogEntry source = contents.pending().current();
        if (rootTable == null || source == null) return;
        LootResultSignature signature = LootResultMatcher.resolve(original,
                ArchaeologyLootRuntimeTracker.toSignatures(remaining()), LootResultPreviewCache.PROVIDER);
        if (signature == null) return;
        if (deliveredCount > 0) {
            recordAcquisition(player, rootTable, source, Map.of(signature.toStoredKey(), deliveredCount));
        }
        contents.consume(signature.toStoredKey(), original.getCount());
    }

    /** 只消费原始库存的待领取余量，并把本次取得量记到领取者名下。 */
    public void acquire(ServerPlayer player, Map<String, Integer> acquired) {
        ResourceLocation rootTable = contents.table();
        ExcavationLogEntry source = contents.pending().current();
        if (rootTable == null || source == null) return;
        Map<String, Integer> actual = intersect(acquired, remaining());
        if (actual.isEmpty()) return;
        recordAcquisition(player, rootTable, source, actual);
        actual.forEach(contents::consume);
    }

    private void recordAcquisition(ServerPlayer player, ResourceLocation rootTable,
                                   ExcavationLogEntry source, Map<String, Integer> actual) {
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) return;
        var level = player.serverLevel();
        GameTimestamp timestamp = new GameTimestamp(level.getGameTime(), level.getDayTime());
        Map<String, Integer> legacyActual = player.getUUID().equals(contents.owner()) ? source.actualLoot() : Map.of();
        Collection<ResourceLocation> tables = source.tableStack() == null ? List.of(rootTable) : source.tableStack();
        List<ResourceLocation> changedTables = new ArrayList<>();
        boolean stateChanged = false;
        try {
            for (ResourceLocation table : tables) {
                Map<String, Integer> found = discoveries.get(table);
                Map<String, Integer> tableLoot = rootTable.equals(table) || found == null
                        ? actual : intersect(actual, found);
                if (tableLoot.isEmpty()) continue;
                if (state.unlockTable(table)) {
                    JournalLogHandler.recordFirstUnlock(player, table, source.lootSource(),
                            timestamp.gameTime(), timestamp.dayTime());
                    stateChanged = true;
                    ArchaeologyJournalServerCatalog.enqueueBaselinePriority(player.server, table);
                }
                stateChanged |= state.recordItemsAcquired(table, ArchaeologyLootRuntimeTracker.toSignatureCounts(tableLoot));
                JournalLogHandler.mergeAcquisition(player,
                        new MergeAcquisition(table, source.withActualLoot(actual, timestamp), legacyActual));
                changedTables.add(table);
            }
            if (stateChanged) {
                JournalStateHandler.syncState(player);
                ArchaeologyChallengeChecker.checkAndGrant(player, state);
                JournalCompletionRewardChecker.checkAndReward(player, state, changedTables);
            }
        } catch (RuntimeException | Error failure) {
            Constants.LOG.error(Constants.LOG_TAG + "库存领取结算失败: table={}, inventory={}, player={}",
                    rootTable, generation, player.getUUID(), failure);
            throw failure;
        }
    }

    private static Map<String, Integer> intersect(Map<String, Integer> counts, Map<String, Integer> limits) {
        Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((signature, count) -> {
            int kept = Math.min(count, limits.getOrDefault(signature, 0));
            if (kept > 0) result.put(signature, kept);
        });
        return result;
    }

    /** 保存到 Lootr 原有的库存 tag，沿用已有物品余量及日志来源字段。 */
    public void write(CompoundTag tag) {
        contents.write(tag);
        if (generation != null) tag.putUUID(GENERATION, generation);
        CompoundTag tables = new CompoundTag();
        discoveries.forEach((table, counts) -> tables.put(table.toString(), LootCounts.writeToNbt(counts)));
        if (!tables.isEmpty()) tag.put(DISCOVERIES, tables);
    }

    /** 旧库存仍可从待定条目恢复其稳定 ID，旧记录的个人实得量带有原所有者。 */
    public void read(CompoundTag tag) {
        contents.read(tag);
        ExcavationLogEntry source = contents.pending().current();
        generation = tag.hasUUID(GENERATION) ? tag.getUUID(GENERATION) : source == null ? null : source.entryId();
        CompoundTag tables = tag.getCompound(DISCOVERIES);
        Map<ResourceLocation, Map<String, Integer>> restored = new LinkedHashMap<>();
        for (String key : tables.getAllKeys()) {
            ResourceLocation table = ResourceLocation.tryParse(key);
            if (table != null) {
                restored.put(table, LootCounts.normalize(LootCounts.readFromNbt(tables.getCompound(key))));
            }
        }
        discoveries = Map.copyOf(restored);
    }
}
