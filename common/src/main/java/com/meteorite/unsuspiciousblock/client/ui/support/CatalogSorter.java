package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.network.chat.Component;

import java.util.Comparator;

/**
 * 考古手册目录排序器。
 * <p>
 * 提供排序方式枚举、比较器构建、图标/tooltip 映射，
 */
public final class CatalogSorter {

    // 排序方式
    public enum SortOrder {
        DEFAULT("default"),
        NAME("name"),
        UNLOCK("unlock"),
        ITEM_COUNT("item_count"),
        FAVORITE("favorite"),
        UPDATE_TIME("update_time");

        private final String key;

        SortOrder(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }

        // 循环切换到下一个排序方式
        public SortOrder next() {
            SortOrder[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    private CatalogSorter() {
    }

    // 根据排序方式和方向返回对应比较器
    public static Comparator<ArchaeologyJournalEntry> getComparator(SortOrder order, boolean descending) {
        Comparator<ArchaeologyJournalEntry> comparator = switch (order) {
            case DEFAULT -> Comparator
                    .comparing((ArchaeologyJournalEntry v) -> !"minecraft".equals(v.id().getNamespace()))
                    .thenComparing(v -> v.id().getNamespace())
                    .thenComparing(ArchaeologyJournalEntry::type)
                    .thenComparing(v -> v.displayName().getString())
                    .thenComparing(v -> v.id().getPath());
            case NAME -> Comparator
                    .comparing((ArchaeologyJournalEntry v) -> v.displayName().getString());
            case UNLOCK -> Comparator
                    .comparing((ArchaeologyJournalEntry v) -> !v.unlocked())
                    .thenComparing(v -> !"minecraft".equals(v.id().getNamespace()))
                    .thenComparing(v -> v.id().getNamespace())
                    .thenComparing(v -> v.displayName().getString());
            case ITEM_COUNT -> Comparator
                    .comparingInt((ArchaeologyJournalEntry v) -> v.items().size()).reversed()
                    .thenComparing(v -> !"minecraft".equals(v.id().getNamespace()))
                    .thenComparing(v -> v.id().getNamespace())
                    .thenComparing(v -> v.displayName().getString());
            case FAVORITE -> Comparator
                    .comparing((ArchaeologyJournalEntry v) -> !v.favorite())
                    .thenComparing(v -> !"minecraft".equals(v.id().getNamespace()))
                    .thenComparing(v -> v.id().getNamespace())
                    .thenComparing(v -> v.displayName().getString());
            case UPDATE_TIME -> updateTimeComparator(descending);
        };
        return descending && order != SortOrder.UPDATE_TIME ? comparator.reversed() : comparator;
    }

    // 更新时间正倒序只反转有效时间戳；无日志条目始终排在有日志条目之后。
    private static Comparator<ArchaeologyJournalEntry> updateTimeComparator(boolean descending) {
        Comparator<ExcavationLogEntry.GameTimestamp> timestampComparator = Comparator
                .comparingLong(ExcavationLogEntry.GameTimestamp::gameTime)
                .thenComparingLong(ExcavationLogEntry.GameTimestamp::dayTime);
        if (descending) {
            timestampComparator = timestampComparator.reversed();
        }
        return Comparator
                .comparing((ArchaeologyJournalEntry entry) -> entry.logRef().latestLogUpdateTimestamp(),
                        Comparator.nullsLast(timestampComparator))
                .thenComparing(entry -> !"minecraft".equals(entry.id().getNamespace()))
                .thenComparing(entry -> entry.id().getNamespace())
                .thenComparing(entry -> entry.displayName().getString());
    }

    // 排序方式 tooltip
    public static Component sortOrderTooltip(SortOrder order) {
        return switch (order) {
            case DEFAULT -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.default");
            case NAME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.name");
            case UNLOCK -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.unlock");
            case ITEM_COUNT -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.item_count");
            case FAVORITE -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.favorite");
            case UPDATE_TIME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.update_time");
        };
    }

    // 排序方向 tooltip
    public static Component sortDirectionTooltip(boolean descending) {
        String key = descending
                ? "screen.unsuspiciousblock.archaeology_journal.sort.descending"
                : "screen.unsuspiciousblock.archaeology_journal.sort.ascending";
        return Component.translatable(key);
    }
}
