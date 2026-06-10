package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.network.chat.Component;

import java.util.Comparator;

/**
 * 考古日志排序器。
 * <p>
 * 提供日志条目的排序方式枚举、比较器构建、图标/tooltip 映射，
 * 遵循与 {@link CatalogSorter} 相同的设计模式。
 */
public final class LogSorter {

    // 排序方式
    public enum SortOrder {
        TIME("time"),
        STRUCTURE("structure"),
        TRIGGER("trigger"),
        SOURCE("source");

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

    private LogSorter() {
    }

    // 根据排序方式和方向返回对应比较器
    public static Comparator<ExcavationLogEntry> getComparator(SortOrder order, boolean descending) {
        Comparator<ExcavationLogEntry> comparator = switch (order) {
            case TIME -> Comparator
                    .comparingLong(ExcavationLogEntry::lastUpdatedGameTime).reversed()
                    .thenComparingLong(ExcavationLogEntry::lastUpdatedDayTime).reversed()
                    .thenComparingLong(ExcavationLogEntry::createdGameTime).reversed()
                    .thenComparingLong(ExcavationLogEntry::createdDayTime).reversed()
                    .thenComparing(ExcavationLogEntry::entryId);
            case STRUCTURE -> Comparator
                    .comparing((ExcavationLogEntry e) ->
                            JournalFormatHelper.formatStructureName(e.structureId()));
            case TRIGGER -> Comparator
                    .comparing((ExcavationLogEntry e) ->
                            JournalFormatHelper.formatTriggerType(e.triggerType()).getString());
            case SOURCE -> Comparator
                    .comparing((ExcavationLogEntry e) ->
                            JournalFormatHelper.formatBlockName(e.sourceBlockId()));
        };
        return descending ? comparator : comparator.reversed();
    }

    // 排序方式图标字符
    public static char sortOrderIcon(SortOrder order) {
        return switch (order) {
            case TIME -> 'T';
            case STRUCTURE -> 'A';
            case TRIGGER -> '!';
            case SOURCE -> 'S';
        };
    }

    // 排序方向图标字符
    public static char sortDirectionIcon(boolean descending) {
        return descending ? '↓' : '↑';
    }

    // 排序方式 tooltip
    public static Component sortOrderTooltip(SortOrder order) {
        return switch (order) {
            case TIME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_sort.time");
            case STRUCTURE -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_sort.structure");
            case TRIGGER -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_sort.trigger");
            case SOURCE -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_sort.source");
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