package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.network.chat.Component;

/**
 * 考古日志分组器。
 * <p>
 * 提供日志条目的分组方式枚举、分组键提取与 tooltip 映射，
 * 遵循与 {@link CatalogSorter} 相同的设计模式。
 */
public final class LogGrouper {

    // 游戏日对应的刻数（与 GameTimeFormatHelper 一致）
    private static final long DAY_TICKS = 24000L;

    // 分组方式
    public enum GroupMode {
        TIME("time"),
        DIMENSION("dimension"),
        BIOME("biome"),
        NOTED("noted");

        private final String key;

        GroupMode(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }

        // 循环切换到下一个分组方式
        public GroupMode next() {
            GroupMode[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    // 时间区间分桶——按游戏日计算
    public enum TimeBucket {
        DAY_1("1d"),
        DAY_3("3d"),
        DAY_7("7d"),
        DAY_30("30d"),
        OLDER("older");

        private final String key;

        TimeBucket(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }
    }

    private LogGrouper() {
    }

    // 根据年龄（刻）计算所属时间区间桶
    public static TimeBucket bucketForAge(long ageTicks) {
        long age = Math.max(0L, ageTicks);
        if (age <= DAY_TICKS) return TimeBucket.DAY_1;
        if (age <= DAY_TICKS * 3L) return TimeBucket.DAY_3;
        if (age <= DAY_TICKS * 7L) return TimeBucket.DAY_7;
        if (age <= DAY_TICKS * 30L) return TimeBucket.DAY_30;
        return TimeBucket.OLDER;
    }

    // 根据分组方式提取日志条目的分组键
    // referenceGameTime 仅 TIME 模式使用，用于计算时间区间桶
    public static String groupKey(GroupMode mode, ExcavationLogEntry entry, long referenceGameTime) {
        return switch (mode) {
            case TIME -> bucketForAge(referenceGameTime - entry.createdGameTime()).key();
            case DIMENSION -> JournalFormatHelper.formatDimensionName(entry.dimensionId());
            case BIOME -> JournalFormatHelper.formatBiomeName(entry.biomeId());
            case NOTED -> entry.hasNote() ? "yes" : "no";
        };
    }

    // 分组方式 tooltip
    public static Component groupModeTooltip(GroupMode mode) {
        return switch (mode) {
            case TIME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.time");
            case DIMENSION -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.dimension");
            case BIOME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.biome");
            case NOTED -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.noted");
        };
    }

    // 组头显示名：组名 + 条目数量
    public static Component groupHeader(GroupMode mode, String groupKey, int count) {
        Component name = switch (mode) {
            case TIME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.bucket." + groupKey);
            case NOTED -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.noted." + groupKey);
            default -> Component.literal(groupKey);
        };
        return Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_group_header",
                name, count);
    }
}
