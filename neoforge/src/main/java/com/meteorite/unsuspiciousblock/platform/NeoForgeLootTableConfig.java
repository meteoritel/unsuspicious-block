package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** NeoForge 战利品表配置——使用 ModConfigSpec / TOML */
public class NeoForgeLootTableConfig implements ILootTableConfig {

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ARCHAEOLOGY_PATH_PREFIXES;
    private static final ModConfigSpec.IntValue MAX_LOG_ENTRIES_PER_TABLE;
    private static final ModConfigSpec.LongValue TRACKING_TIMEOUT_TICKS;

    public static final ModConfigSpec CONFIG_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("loot_table");
        ARCHAEOLOGY_PATH_PREFIXES = builder
                .comment("需要追踪的战利品表匹配规则列表。",
                        "语法：[命名空间:路径]。指定命名空间时仅匹配该命名空间，省略时匹配所有命名空间。",
                        "路径以 / 结尾表示前缀匹配（命中该前缀下所有表），否则为精确匹配（仅命中单个表）。",
                        "例：minecraft:archaeology/desert_well -> 单表、unsuspiciousblock:archaeology/ -> 指定模组、archaeology/ -> 所有命名空间。",
                        "",
                        "List of loot table matching rules to track.",
                        "Syntax: [namespace:path]. With namespace, only that namespace is matched; without, all namespaces.",
                        "Path ending with / is a prefix match (all tables under that prefix); otherwise an exact match (single table).",
                        "e.g. minecraft:archaeology/desert_well (single), unsuspiciousblock:archaeology/ (specific mod), archaeology/ (all namespaces).")
                .translation("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes")
                .defineListAllowEmpty("archaeology_path_prefixes",
                        () -> List.of("archaeology/", "archeology/", "minecraft:gameplay/fishing",
                                "unsuspiciousblock:gameplay/fossil_hunter/", "gameplay/fishing/"),
                        () -> "",
                        obj -> obj instanceof String s && !s.isBlank());
        builder.pop();

        builder.push("journal");
        MAX_LOG_ENTRIES_PER_TABLE = builder
                .comment("单张战利品表保留的日志条目上限。超出后自动丢弃最旧条目。取值范围 "
                        + ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE + "–" + ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE
                        + "。默认 " + ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE + "。",
                        "",
                        "Max log entries kept per loot table. Oldest entries are dropped when exceeded. Range "
                        + ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE + "–" + ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE
                        + ". Default " + ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE + ".")
                .translation("unsuspiciousblock.configgui.journal.max_log_entries_per_table")
                .defineInRange("max_log_entries_per_table",
                        ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE,
                        ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE,
                        ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE);
        TRACKING_TIMEOUT_TICKS = builder
                .comment("战利品箱追踪超时（游戏刻）。超时后自动结算并清除追踪状态。取值范围 "
                        + ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS + "–" + ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS
                        + "。",
                        "",
                        "Loot container tracking timeout (ticks). Auto-settles and clears tracking state on expiry. Range "
                        + ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS + "–" + ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS
                        + ".")
                .translation("unsuspiciousblock.configgui.journal.tracking_timeout_ticks")
                .defineInRange("tracking_timeout_ticks",
                        ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS,
                        ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS,
                        ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS);
        builder.pop();

        CONFIG_SPEC = builder.build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getArchaeologyPathPrefixes() {
        return (List<String>) ARCHAEOLOGY_PATH_PREFIXES.get();
    }

    @Override
    public int getMaxLogEntriesPerTable() {
        return MAX_LOG_ENTRIES_PER_TABLE.get();
    }

    @Override
    public long getTrackingTimeoutTicks() {
        return TRACKING_TIMEOUT_TICKS.get();
    }
}
