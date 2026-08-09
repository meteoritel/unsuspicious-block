package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** NeoForge 战利品表配置——使用 ModConfigSpec / TOML */
public class NeoForgeLootTableConfig implements ILootTableConfig, ISpiritCatConfig {

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ARCHAEOLOGY_PATH_PREFIXES;
    private static final ModConfigSpec.IntValue MAX_LOG_ENTRIES_PER_TABLE;
    private static final ModConfigSpec.LongValue TRACKING_TIMEOUT_TICKS;
    private static final ModConfigSpec.IntValue MESSENGER_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue SWORDSMAN_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue MERCHANT_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue INVULNERABILITY_DURATION_TICKS;
    private static final ModConfigSpec.IntValue RESISTANCE_DURATION_TICKS;
    private static final ModConfigSpec.IntValue FIRE_RESISTANCE_DURATION_TICKS;

    public static final ModConfigSpec CONFIG_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("loot_table");
        ARCHAEOLOGY_PATH_PREFIXES = builder
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

        builder.push("spirit_cat");
        MESSENGER_LIFETIME_TICKS = defineNpcLifetime(builder, "messenger_lifetime_ticks",
                DEFAULT_MESSENGER_LIFETIME_TICKS, "信使猫猫最长现世时间", "Messenger cat maximum lifetime");
        SWORDSMAN_LIFETIME_TICKS = defineNpcLifetime(builder, "swordsman_lifetime_ticks",
                DEFAULT_SWORDSMAN_LIFETIME_TICKS, "剑士猫猫最长现世时间", "Swordsman cat maximum lifetime");
        MERCHANT_LIFETIME_TICKS = defineNpcLifetime(builder, "merchant_lifetime_ticks",
                DEFAULT_MERCHANT_LIFETIME_TICKS, "商人猫猫最长现世时间", "Merchant cat maximum lifetime");
        INVULNERABILITY_DURATION_TICKS = defineEffectDuration(builder, "invulnerability_duration_ticks",
                DEFAULT_INVULNERABILITY_DURATION_TICKS, "九命纯无敌持续时间", "Nine Lives invulnerability duration");
        RESISTANCE_DURATION_TICKS = defineEffectDuration(builder, "resistance_duration_ticks",
                DEFAULT_RESISTANCE_DURATION_TICKS, "九命抗性提升 II 持续时间", "Nine Lives Resistance II duration");
        FIRE_RESISTANCE_DURATION_TICKS = defineEffectDuration(builder, "fire_resistance_duration_ticks",
                DEFAULT_FIRE_RESISTANCE_DURATION_TICKS, "九命防火 I 持续时间", "Nine Lives Fire Resistance I duration");
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
