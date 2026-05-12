package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** NeoForge 战利品表配置——使用 ModConfigSpec / TOML */
public class NeoForgeLootTableConfig implements ILootTableConfig {

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ARCHAEOLOGY_PATH_PREFIXES;

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

        CONFIG_SPEC = builder.build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getArchaeologyPathPrefixes() {
        return (List<String>) (List<?>) ARCHAEOLOGY_PATH_PREFIXES.get();
    }
}
