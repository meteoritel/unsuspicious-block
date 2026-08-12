package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * NeoForge 配置实现——战利品追踪使用 SERVER spec，灵体猫参数保留 COMMON spec。
 */
public class NeoForgeLootTableConfig implements ILootTableConfig, ISpiritCatConfig {

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ARCHAEOLOGY_PATH_PREFIXES;
    private static final ModConfigSpec.IntValue MAX_LOG_ENTRIES_PER_TABLE;
    private static final ModConfigSpec.LongValue TRACKING_TIMEOUT_TICKS;
    private static final ModConfigSpec.BooleanValue MIGRATED_FROM_COMMON_CONFIG;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> LEGACY_ARCHAEOLOGY_PATH_PREFIXES;
    private static final ModConfigSpec.IntValue LEGACY_MAX_LOG_ENTRIES_PER_TABLE;
    private static final ModConfigSpec.LongValue LEGACY_TRACKING_TIMEOUT_TICKS;
    private static final ModConfigSpec.IntValue MESSENGER_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue SWORDSMAN_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue MERCHANT_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue INVULNERABILITY_DURATION_TICKS;
    private static final ModConfigSpec.IntValue RESISTANCE_DURATION_TICKS;
    private static final ModConfigSpec.IntValue FIRE_RESISTANCE_DURATION_TICKS;

    public static final ModConfigSpec SERVER_CONFIG_SPEC;
    public static final ModConfigSpec COMMON_CONFIG_SPEC;

    static {
        ModConfigSpec.Builder serverBuilder = new ModConfigSpec.Builder();

        serverBuilder.push("loot_table");
        ARCHAEOLOGY_PATH_PREFIXES = serverBuilder
                .comment("需要追踪的战利品表匹配规则列表。",
                        "语法：[命名空间:路径]。指定命名空间时仅匹配该命名空间，省略时匹配所有命名空间。",
                        "路径以 / 结尾表示前缀匹配（命中该前缀下所有表），否则为精确匹配（仅命中单个表）。",
                        "直接命中规则的表会显示为目录根表，即使它同时被其他表引用；仅通过父表引用收录的表只显示为子表。",
                        "例：minecraft:archaeology/desert_well -> 单表、unsuspiciousblock:archaeology/ -> 指定模组、archaeology/ -> 所有命名空间。",
                        "",
                        "List of loot table matching rules to track.",
                        "Syntax: [namespace:path]. With namespace, only that namespace is matched; without, all namespaces.",
                        "Path ending with / is a prefix match (all tables under that prefix); otherwise an exact match (single table).",
                        "A directly matched table is a directory root even when referenced by another table; reference-only tables appear only as children.",
                        "e.g. minecraft:archaeology/desert_well (single), unsuspiciousblock:archaeology/ (specific mod), archaeology/ (all namespaces).")
                .translation("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes")
                .defineListAllowEmpty("archaeology_path_prefixes",
                        () -> ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES,
                        () -> "",
                        obj -> obj instanceof String s && !s.isBlank());
        serverBuilder.pop();

        serverBuilder.push("journal");
        MAX_LOG_ENTRIES_PER_TABLE = serverBuilder
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
        TRACKING_TIMEOUT_TICKS = serverBuilder
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
        serverBuilder.pop();
        MIGRATED_FROM_COMMON_CONFIG = serverBuilder
                .comment("内部迁移标记：首次加载世界时从旧 COMMON 配置复制战利品追踪设置。",
                        "Internal migration marker for legacy COMMON loot tracking settings.")
                .define("migrated_from_common_config", false);
        SERVER_CONFIG_SPEC = serverBuilder.build();

        ModConfigSpec.Builder commonBuilder = new ModConfigSpec.Builder();
        commonBuilder.push("loot_table");
        LEGACY_ARCHAEOLOGY_PATH_PREFIXES = commonBuilder
                .comment("旧版全局追踪规则，仅用于新世界首次迁移。",
                        "Legacy global tracking rules, retained only as migration defaults for new worlds.")
                .defineListAllowEmpty("archaeology_path_prefixes",
                        () -> ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES,
                        () -> "",
                        obj -> obj instanceof String s && !s.isBlank());
        commonBuilder.pop();
        commonBuilder.push("journal");
        LEGACY_MAX_LOG_ENTRIES_PER_TABLE = commonBuilder
                .comment("旧版全局日志上限，仅用于新世界首次迁移。",
                        "Legacy global log limit, retained only as a migration default.")
                .defineInRange("max_log_entries_per_table",
                        ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE,
                        ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE,
                        ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE);
        LEGACY_TRACKING_TIMEOUT_TICKS = commonBuilder
                .comment("旧版全局追踪超时，仅用于新世界首次迁移。",
                        "Legacy global tracking timeout, retained only as a migration default.")
                .defineInRange("tracking_timeout_ticks",
                        ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS,
                        ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS,
                        ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS);
        commonBuilder.pop();
        commonBuilder.push("spirit_cat");
        MESSENGER_LIFETIME_TICKS = defineNpcLifetime(commonBuilder, "messenger_lifetime_ticks",
                DEFAULT_MESSENGER_LIFETIME_TICKS, "信使猫猫最长现世时间", "Messenger cat maximum lifetime");
        SWORDSMAN_LIFETIME_TICKS = defineNpcLifetime(commonBuilder, "swordsman_lifetime_ticks",
                DEFAULT_SWORDSMAN_LIFETIME_TICKS, "剑士猫猫最长现世时间", "Swordsman cat maximum lifetime");
        MERCHANT_LIFETIME_TICKS = defineNpcLifetime(commonBuilder, "merchant_lifetime_ticks",
                DEFAULT_MERCHANT_LIFETIME_TICKS, "商人猫猫最长现世时间", "Merchant cat maximum lifetime");
        INVULNERABILITY_DURATION_TICKS = defineEffectDuration(commonBuilder, "invulnerability_duration_ticks",
                DEFAULT_INVULNERABILITY_DURATION_TICKS, "九命纯无敌持续时间", "Nine Lives invulnerability duration");
        RESISTANCE_DURATION_TICKS = defineEffectDuration(commonBuilder, "resistance_duration_ticks",
                DEFAULT_RESISTANCE_DURATION_TICKS, "九命抗性提升 II 持续时间", "Nine Lives Resistance II duration");
        FIRE_RESISTANCE_DURATION_TICKS = defineEffectDuration(commonBuilder, "fire_resistance_duration_ticks",
                DEFAULT_FIRE_RESISTANCE_DURATION_TICKS, "九命防火 I 持续时间", "Nine Lives Fire Resistance I duration");
        commonBuilder.pop();
        COMMON_CONFIG_SPEC = commonBuilder.build();
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

    @Override
    @SuppressWarnings("unchecked")
    public void loadForServer(net.minecraft.server.MinecraftServer server) {
        if (MIGRATED_FROM_COMMON_CONFIG.get()) {
            return;
        }
        ARCHAEOLOGY_PATH_PREFIXES.set(List.copyOf((List<String>) LEGACY_ARCHAEOLOGY_PATH_PREFIXES.get()));
        MAX_LOG_ENTRIES_PER_TABLE.set(LEGACY_MAX_LOG_ENTRIES_PER_TABLE.get());
        TRACKING_TIMEOUT_TICKS.set(LEGACY_TRACKING_TIMEOUT_TICKS.get());
        MIGRATED_FROM_COMMON_CONFIG.set(true);
        SERVER_CONFIG_SPEC.save();
    }

    @Override
    public int getMessengerLifetimeTicks() {
        return MESSENGER_LIFETIME_TICKS.get();
    }

    @Override
    public int getSwordsmanLifetimeTicks() {
        return SWORDSMAN_LIFETIME_TICKS.get();
    }

    @Override
    public int getMerchantLifetimeTicks() {
        return MERCHANT_LIFETIME_TICKS.get();
    }

    @Override
    public int getInvulnerabilityDurationTicks() {
        return INVULNERABILITY_DURATION_TICKS.get();
    }

    @Override
    public int getResistanceDurationTicks() {
        return RESISTANCE_DURATION_TICKS.get();
    }

    @Override
    public int getFireResistanceDurationTicks() {
        return FIRE_RESISTANCE_DURATION_TICKS.get();
    }

    private static ModConfigSpec.IntValue defineNpcLifetime(ModConfigSpec.Builder builder, String key,
                                                             int defaultValue, String cn, String en) {
        return builder.comment(cn + "（游戏刻）。", en + " (ticks).")
                .defineInRange(key, defaultValue, MIN_NPC_LIFETIME_TICKS, MAX_NPC_LIFETIME_TICKS);
    }

    private static ModConfigSpec.IntValue defineEffectDuration(ModConfigSpec.Builder builder, String key,
                                                                int defaultValue, String cn, String en) {
        return builder.comment(cn + "（游戏刻）。", en + " (ticks).")
                .defineInRange(key, defaultValue, MIN_EFFECT_DURATION_TICKS, MAX_EFFECT_DURATION_TICKS);
    }
}
