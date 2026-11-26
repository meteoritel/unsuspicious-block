package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState.TableLogHistory;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry.GameTimestamp;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 日志摘要的线路形态，表元数据和欢迎页预览可独立于历史展示。 */
public final class JournalLogSummary {
    private static final Comparator<GameTimestamp> TIME = Comparator.comparingLong(GameTimestamp::gameTime)
            .thenComparingLong(GameTimestamp::dayTime);
    private static final Comparator<ExcavationLogEntry> RECENCY = Comparator
            .comparing(ExcavationLogEntry::lastUpdated, TIME)
            .thenComparing(ExcavationLogEntry::created, TIME)
            .thenComparing(ExcavationLogEntry::entryId);

    private JournalLogSummary() {}

    /** 目录排序及单表统计。 */
    public record Table(@Nullable Long firstGameTime, @Nullable Long firstDayTime, @Nullable LootSourceType firstSource,
                        @Nullable GameTimestamp latest, int count, int noted, int retention) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            if (firstGameTime != null) {
                tag.putLong("first_game", firstGameTime);
                tag.putLong("first_day", firstDayTime == null ? firstGameTime : firstDayTime);
            }
            if (firstSource != null) tag.putString("first_source", firstSource.id().toString());
            if (latest != null) {
                tag.putLong("latest_game", latest.gameTime());
                tag.putLong("latest_day", latest.dayTime());
            }
            tag.putInt("count", count);
            tag.putInt("noted", noted);
            tag.putInt("retention", retention);
            return tag;
        }

        public static Table fromTag(CompoundTag tag) {
            int count = tag.getInt("count");
            int noted = tag.getInt("noted");
            int retention = tag.getInt("retention");
            if (noted < 0 || noted > count || retention < 1) {
                throw new IllegalArgumentException("考古日志表摘要无效");
            }
            boolean first = tag.contains("first_game", Tag.TAG_LONG);
            return new Table(first ? tag.getLong("first_game") : null, first ? tag.getLong("first_day") : null,
                    tag.contains("first_source", Tag.TAG_STRING)
                            ? LootSourceType.fromId(tag.getString("first_source")) : null,
                    tag.contains("latest_game", Tag.TAG_LONG)
                            ? new GameTimestamp(tag.getLong("latest_game"), tag.getLong("latest_day")) : null,
                    count, noted, retention);
        }

        public Table emptyEntries() {
            return new Table(firstGameTime, firstDayTime, firstSource, null, 0, 0, retention);
        }
    }

    /** 单个最新根表记录的显示字段。 */
    public record Preview(ResourceLocation tableId, GameTimestamp updated, Map<String, Integer> loot) {
        private CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("table", tableId.toString());
            tag.putLong("game", updated.gameTime());
            tag.putLong("day", updated.dayTime());
            CompoundTag items = new CompoundTag();
            loot.forEach(items::putInt);
            tag.put("items", items);
            return tag;
        }

        private static Preview fromTag(CompoundTag tag) {
            CompoundTag items = tag.getCompound("items");
            Map<String, Integer> loot = new LinkedHashMap<>();
            for (String item : items.getAllKeys()) loot.put(item, items.getInt(item));
            return new Preview(ResourceLocation.parse(tag.getString("table")),
                    new GameTimestamp(tag.getLong("game"), tag.getLong("day")), Map.copyOf(loot));
        }
    }

    /** 全局数据随摘要和日志事务一起提交。 */
    public record Totals(int limit, int count, int noted, long archaeology, long containers, @Nullable Preview latest) {
        public static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, null);

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("limit", limit);
            tag.putInt("count", count);
            tag.putInt("noted", noted);
            tag.putLong("archaeology", archaeology);
            tag.putLong("containers", containers);
            if (latest != null) tag.put("latest", latest.toTag());
            return tag;
        }

        public static Totals fromTag(CompoundTag tag) {
            int limit = tag.getInt("limit");
            int count = tag.getInt("count");
            int noted = tag.getInt("noted");
            long archaeology = tag.getLong("archaeology");
            long containers = tag.getLong("containers");
            if (limit < 1 || noted < 0 || noted > count || archaeology < 0 || containers < 0) {
                throw new IllegalArgumentException("考古日志汇总无效");
            }
            return new Totals(limit, count, noted, archaeology, containers,
                    tag.contains("latest", Tag.TAG_COMPOUND) ? Preview.fromTag(tag.getCompound("latest")) : null);
        }
    }

    record Projection(Table summary, long archaeology, long containers, @Nullable ExcavationLogEntry latestRoot) {}

    static Projection project(ResourceLocation tableId, TableLogHistory history) {
        GameTimestamp latest = null;
        ExcavationLogEntry latestRoot = null;
        int noted = 0;
        for (ExcavationLogEntry entry : history.getEntries()) {
            if (entry.hasNote()) noted++;
            if (latest == null || TIME.compare(entry.lastUpdated(), latest) > 0) latest = entry.lastUpdated();
            if (entry.tableStack() != null && !entry.tableStack().isEmpty()
                    && !tableId.equals(entry.tableStack().getFirst())) continue;
            if (latestRoot == null || RECENCY.compare(entry, latestRoot) > 0) latestRoot = entry;
        }
        Table summary = new Table(history.getFirstUnlockedGameTime(), history.getFirstUnlockedDayTime(),
                history.getFirstUnlockLootSource(), latest, history.getTotalEntryCount(), noted, history.getRetentionLimit());
        return new Projection(summary, history.getLifetimeEntryCount(LootSourceType.ARCHAEOLOGY),
                history.getLifetimeEntryCount(LootSourceType.LOOT_CONTAINER), latestRoot);
    }

    static Totals totals(Map<ResourceLocation, Projection> tables, int limit) {
        long count = 0;
        long noted = 0;
        long archaeology = 0;
        long containers = 0;
        ResourceLocation latestTable = null;
        ExcavationLogEntry latest = null;
        for (Map.Entry<ResourceLocation, Projection> table : tables.entrySet()) {
            Projection projection = table.getValue();
            count += projection.summary().count();
            noted += projection.summary().noted();
            archaeology += Math.min(Long.MAX_VALUE - archaeology, projection.archaeology());
            containers += Math.min(Long.MAX_VALUE - containers, projection.containers());
            if (projection.latestRoot() != null && (latest == null || RECENCY.compare(projection.latestRoot(), latest) > 0)) {
                latest = projection.latestRoot();
                latestTable = table.getKey();
            }
        }
        Preview preview = latest == null ? null : new Preview(latestTable, latest.lastUpdated(),
                latest.actualLoot().isEmpty() ? latest.expectedLoot() : latest.actualLoot());
        return new Totals(limit, (int) Math.min(Integer.MAX_VALUE, count), (int) Math.min(Integer.MAX_VALUE, noted),
                archaeology, containers, preview);
    }
}
