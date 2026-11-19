package com.meteorite.unsuspiciousblock.core.loottable;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTablePattern;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 世界保存的战利品表规则和参数覆盖。 */
public record LootTrackingSettings(boolean followBuiltinRules, List<String> disabledBuiltinRules,
                                   List<String> additionalTrackingRules, List<String> excludedLootTables,
                                   @Nullable List<String> signatureExcludedComponents,
                                   @Nullable Integer maxLogEntriesPerTable,
                                   @Nullable Long trackingTimeoutTicks) {
    public static final int MIN_LOG_ENTRIES = 64;
    public static final int MAX_LOG_ENTRIES = 4096;
    public static final int DEFAULT_LOG_ENTRIES = 512;
    public static final long MIN_TIMEOUT_TICKS = 600L;
    public static final long MAX_TIMEOUT_TICKS = 60000L;
    public static final long DEFAULT_TIMEOUT_TICKS = 6000L;
    private static final List<String> DEFAULT_SIGNATURE_EXCLUSIONS = List.of("relics:data");

    public static final LootTrackingSettings DEFAULT = new LootTrackingSettings(
            true, List.of(), List.of(), List.of(), null, null, null);
    static final LootTrackingSettings EMPTY = new LootTrackingSettings(
            false, List.of(), List.of(), List.of(), null, null, null);

    public LootTrackingSettings {
        disabledBuiltinRules = disabledBuiltinRules.stream().map(LootTrackingSettings::requireRuleId)
                .distinct().sorted().toList();
        additionalTrackingRules = additionalTrackingRules.stream().map(LootTrackingSettings::requireSelector)
                .distinct().sorted().toList();
        excludedLootTables = excludedLootTables.stream().map(LootTrackingSettings::requireExactId)
                .distinct().sorted().toList();
        if (signatureExcludedComponents != null) {
            signatureExcludedComponents = signatureExcludedComponents.stream().map(LootTrackingSettings::requireExactId)
                    .distinct().sorted().toList();
        }
        if (maxLogEntriesPerTable != null
                && (maxLogEntriesPerTable < MIN_LOG_ENTRIES || maxLogEntriesPerTable > MAX_LOG_ENTRIES)) {
            throw new IllegalArgumentException("max_log_entries_per_table: " + maxLogEntriesPerTable);
        }
        if (trackingTimeoutTicks != null
                && (trackingTimeoutTicks < MIN_TIMEOUT_TICKS || trackingTimeoutTicks > MAX_TIMEOUT_TICKS)) {
            throw new IllegalArgumentException("tracking_timeout_ticks: " + trackingTimeoutTicks);
        }
    }

    public List<String> signatureExclusions() {
        return signatureExcludedComponents == null ? DEFAULT_SIGNATURE_EXCLUSIONS : signatureExcludedComponents;
    }

    public int logLimit() {
        return maxLogEntriesPerTable == null ? DEFAULT_LOG_ENTRIES : maxLogEntriesPerTable;
    }

    public long timeoutTicks() {
        return trackingTimeoutTicks == null ? DEFAULT_TIMEOUT_TICKS : trackingTimeoutTicks;
    }

    private static String requireRuleId(String id) {
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("disabled_builtin_rules: " + id);
        return id;
    }

    private static String requireSelector(String selector) {
        LootTablePattern pattern = LootTablePattern.parse(selector);
        if (pattern == null || !pattern.isValidSelector()) {
            throw new IllegalArgumentException("additional_tracking_rules: " + selector);
        }
        return pattern.selector();
    }

    static boolean isExactId(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed != null && !parsed.getPath().isEmpty() && id.equals(parsed.toString());
    }

    private static String requireExactId(String id) {
        if (!isExactId(id)) throw new IllegalArgumentException("expected namespace:path: " + id);
        return id;
    }
}
