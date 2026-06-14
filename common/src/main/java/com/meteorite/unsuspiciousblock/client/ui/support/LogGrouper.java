package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.network.chat.Component;

/**
 * 考古日志分组器。
 * <p>
 * 提供日志条目的分组方式枚举、分组键提取、图标/tooltip 映射，
 * 遵循与 {@link LogSorter} 相同的设计模式。
 */
public final class LogGrouper {

    // 分组方式
    public enum GroupMode {
        NONE("none"),
        STRUCTURE("structure"),
        DIMENSION("dimension"),
        SOURCE("source");

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

    private LogGrouper() {
    }

    // 根据分组方式提取日志条目的分组键
    public static String groupKey(GroupMode mode, ExcavationLogEntry entry) {
        return switch (mode) {
            case NONE -> "";
            case STRUCTURE -> JournalFormatHelper.formatStructureName(entry.structureId());
            case DIMENSION -> JournalFormatHelper.formatDimensionName(entry.dimensionId());
            case SOURCE -> JournalFormatHelper.formatLootSource(entry.lootSource()).getString();
        };
    }

    // 分组方式图标字符
    public static char groupModeIcon(GroupMode mode) {
        return switch (mode) {
            case NONE -> '☐';     // 平铺（无分组）
            case STRUCTURE -> '⊞'; // 按结构分组
            case DIMENSION -> '◈'; // 按维度分组
            case SOURCE -> '◆';    // 按来源分组
        };
    }

    // 分组方式 tooltip
    public static Component groupModeTooltip(GroupMode mode) {
        return switch (mode) {
            case NONE -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.none");
            case STRUCTURE -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.structure");
            case DIMENSION -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.dimension");
            case SOURCE -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_group.source");
        };
    }

    // 组头显示名：组名 + 条目数量
    public static Component groupHeader(GroupMode mode, String groupName, int count) {
        return Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_group_header",
                groupName, count);
    }
}