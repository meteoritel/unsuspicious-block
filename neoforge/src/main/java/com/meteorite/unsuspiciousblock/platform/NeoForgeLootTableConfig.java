package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** NeoForge 战利品表配置——使用 ModConfigSpec / TOML */
public class NeoForgeLootTableConfig implements ILootTableConfig {

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ARCHAEOLOGY_PATH_PREFIXES;
    private static final ModConfigSpec.IntValue MAX_LOG_ENTRIES_PER_TABLE;
    private static final ModConfigSpec.IntValue CACHE_ME_IF_YOU_CAN_THRESHOLD;

    public static final ModConfigSpec CONFIG_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("loot_table");
        ARCHAEOLOGY_PATH_PREFIXES = builder
                .comment("考古战利品表路径前缀列表。仅具有这些前缀的战利品表会被追踪。")
                .translation("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes")
                .defineListAllowEmpty("archaeology_path_prefixes",
                        () -> List.of("archaeology/", "archeology/"),
                        () -> "",
                        obj -> obj instanceof String s && !s.isBlank());
        builder.pop();

        builder.push("journal");
        MAX_LOG_ENTRIES_PER_TABLE = builder
                .comment("单张战利品表的日志条目上限。超过上限时自动移除最旧的条目。")
                .translation("unsuspiciousblock.configgui.journal.max_log_entries_per_table")
                .defineInRange("max_log_entries_per_table", 1024, 64, 4096);
        CACHE_ME_IF_YOU_CAN_THRESHOLD = builder
                .comment("\"缓存大师\"成就的日志总条数门槛。")
                .translation("unsuspiciousblock.configgui.journal.cache_me_if_you_can_threshold")
                .defineInRange("cache_me_if_you_can_threshold", 1024, 64, 65536);
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
    public int getCacheMeIfYouCanThreshold() {
        return CACHE_ME_IF_YOU_CAN_THRESHOLD.get();
    }
}
