package com.meteorite.unsuspiciousblock.core.loottable;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTablePattern;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 旧世界追踪设置；平台适配器按各自的持久化方式解析来源。 */
public record LegacyLootTrackingConfig(@Nullable List<String> selectors, List<String> exclusions,
                                       @Nullable List<String> signatureExclusions,
                                       @Nullable Integer logLimit, @Nullable Long timeoutTicks) {
    // 固定迁移原点，当前内置规则独立演进。
    private static final List<LootTrackingRules.Builtin> V1_5_2_DEFAULTS = List.of(
            new LootTrackingRules.Builtin("archaeology", "archaeology/"),
            new LootTrackingRules.Builtin("archeology", "archeology/"),
            new LootTrackingRules.Builtin("pots", "pots/"),
            new LootTrackingRules.Builtin("fishing", "minecraft:gameplay/fishing"),
            new LootTrackingRules.Builtin("buried_treasure", "minecraft:chests/buried_treasure"),
            new LootTrackingRules.Builtin("ancient_city", "minecraft:chests/ancient_city"),
            new LootTrackingRules.Builtin("ancient_city_ice_box", "minecraft:chests/ancient_city_ice_box"),
            new LootTrackingRules.Builtin("mod_fishing", "unsuspiciousblock:gameplay/fishing/"),
            new LootTrackingRules.Builtin("fossil_hunter", "unsuspiciousblock:gameplay/fossil_hunter/")
    );

    public static List<String> originalSelectors() {
        return V1_5_2_DEFAULTS.stream().map(LootTrackingRules.Builtin::selector).toList();
    }

    /** 将旧清单中的管理员调整转换为世界覆盖。 */
    public LootTrackingSettings migrate() {
        return convert(true);
    }

    LootTrackingSettings asReadOnlySettings() {
        return convert(false);
    }

    private LootTrackingSettings convert(boolean inheritNewDefaults) {
        List<String> source = selectors == null ? originalSelectors() : selectors;
        Set<String> additional = new LinkedHashSet<>();
        for (String selector : source) {
            LootTablePattern pattern = LootTablePattern.parse(selector);
            if (pattern != null && pattern.isValidSelector()) {
                additional.add(pattern.selector());
            } else {
                Constants.LOG.warn("{}Ignoring invalid legacy loot rule {}", Constants.LOG_TAG, selector);
            }
        }
        List<String> disabled = new ArrayList<>();
        boolean followsDefaults = inheritNewDefaults && !additional.isEmpty();
        if (followsDefaults) {
            for (LootTrackingRules.Builtin rule : V1_5_2_DEFAULTS) {
                if (!additional.remove(rule.selector())) disabled.add(rule.id());
            }
        }
        return new LootTrackingSettings(followsDefaults, disabled, List.copyOf(additional), validIds(exclusions),
                signatureExclusions == null ? null : validComponentIds(signatureExclusions),
                logLimit != null && logLimit >= LootTrackingSettings.MIN_LOG_ENTRIES
                        && logLimit <= LootTrackingSettings.MAX_LOG_ENTRIES ? logLimit : null,
                timeoutTicks != null && timeoutTicks >= LootTrackingSettings.MIN_TIMEOUT_TICKS
                        && timeoutTicks <= LootTrackingSettings.MAX_TIMEOUT_TICKS ? timeoutTicks : null);
    }

    private static List<String> validComponentIds(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            ResourceLocation location = ResourceLocation.tryParse(value);
            if (location != null && !location.getPath().isEmpty()) {
                result.add(location.toString());
            } else {
                Constants.LOG.warn("{}Ignoring invalid legacy signature component {}", Constants.LOG_TAG, value);
            }
        }
        return result;
    }

    private static List<String> validIds(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (LootTrackingSettings.isExactId(value)) {
                result.add(value);
            } else {
                Constants.LOG.warn("{}Ignoring invalid legacy loot configuration ID {}", Constants.LOG_TAG, value);
            }
        }
        return result;
    }
}
