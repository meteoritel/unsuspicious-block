package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.core.loottable.LegacyLootTrackingConfig;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingSettings;
import com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** NeoForge 灵体猫 COMMON 配置，并为旧世界导入保留原 COMMON 字段。 */
public final class NeoForgeSpiritCatConfig implements ISpiritCatConfig {

    private static final ModConfigSpec.ConfigValue<List<? extends String>> LEGACY_ARCHAEOLOGY_PATH_PREFIXES;
    private static final ModConfigSpec.IntValue LEGACY_MAX_LOG_ENTRIES_PER_TABLE;
    private static final ModConfigSpec.LongValue LEGACY_TRACKING_TIMEOUT_TICKS;
    private static final ModConfigSpec.IntValue MESSENGER_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue SWORDSMAN_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue MERCHANT_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue INVULNERABILITY_DURATION_TICKS;
    private static final ModConfigSpec.IntValue RESISTANCE_DURATION_TICKS;
    private static final ModConfigSpec.IntValue FIRE_RESISTANCE_DURATION_TICKS;

    public static final ModConfigSpec COMMON_CONFIG_SPEC;

    static {
        ModConfigSpec.Builder commonBuilder = new ModConfigSpec.Builder();
        commonBuilder.push("loot_table");
        LEGACY_ARCHAEOLOGY_PATH_PREFIXES = commonBuilder
                .comment("旧版全局追踪规则，仅用于新世界首次迁移。",
                        "Legacy global tracking rules, retained only as migration defaults for new worlds.")
                .defineListAllowEmpty("archaeology_path_prefixes",
                        LegacyLootTrackingConfig::originalSelectors,
                        () -> "",
                        obj -> obj instanceof String s && !s.isBlank());
        commonBuilder.pop();
        commonBuilder.push("journal");
        LEGACY_MAX_LOG_ENTRIES_PER_TABLE = commonBuilder
                .comment("旧版全局日志上限，仅用于新世界首次迁移。",
                        "Legacy global log limit, retained only as a migration default.")
                .defineInRange("max_log_entries_per_table",
                        LootTrackingSettings.DEFAULT_LOG_ENTRIES,
                        LootTrackingSettings.MIN_LOG_ENTRIES,
                        LootTrackingSettings.MAX_LOG_ENTRIES);
        LEGACY_TRACKING_TIMEOUT_TICKS = commonBuilder
                .comment("旧版全局追踪超时，仅用于新世界首次迁移。",
                        "Legacy global tracking timeout, retained only as a migration default.")
                .defineInRange("tracking_timeout_ticks",
                        LootTrackingSettings.DEFAULT_TIMEOUT_TICKS,
                        LootTrackingSettings.MIN_TIMEOUT_TICKS,
                        LootTrackingSettings.MAX_TIMEOUT_TICKS);
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

    static LegacyLootTrackingConfig legacyTrackingDefaults() {
        return new LegacyLootTrackingConfig(List.copyOf(LEGACY_ARCHAEOLOGY_PATH_PREFIXES.get()), List.of(),
                null, LEGACY_MAX_LOG_ENTRIES_PER_TABLE.get(), LEGACY_TRACKING_TIMEOUT_TICKS.get());
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
