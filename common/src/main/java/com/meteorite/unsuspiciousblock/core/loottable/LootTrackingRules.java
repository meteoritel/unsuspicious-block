package com.meteorite.unsuspiciousblock.core.loottable;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTablePattern;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 从世界覆盖解析收录根表规则，目录负责沿引用收录子表。 */
public final class LootTrackingRules {
    /** 稳定标识独立于选择器，世界可以持续禁用同一内置规则。 */
    public record Builtin(String id, String selector) {}

    public static final List<Builtin> BUILTINS = List.of(
            new Builtin("archaeology", "archaeology/"),
            new Builtin("archeology", "archeology/"),
            new Builtin("pots", "pots/"),
            new Builtin("fishing", "minecraft:gameplay/fishing"),
            new Builtin("buried_treasure", "minecraft:chests/buried_treasure"),
            new Builtin("ancient_city", "minecraft:chests/ancient_city"),
            new Builtin("ancient_city_ice_box", "minecraft:chests/ancient_city_ice_box"),
            new Builtin("mod_fishing", "unsuspiciousblock:gameplay/fishing/"),
            new Builtin("fossil_hunter", "unsuspiciousblock:gameplay/fossil_hunter/"),
            new Builtin("panning", "unsuspiciousblock:gameplay/panning/")
    );

    /** 根表候选资格与直接规则来源。 */
    public enum Match {
        EXCLUDED, UNMATCHED, BUILTIN, ADDITIONAL, BOTH;

        public boolean isRootCandidate() {
            return this == BUILTIN || this == ADDITIONAL || this == BOTH;
        }
    }

    /** 单表覆盖编辑，不改变共享前缀规则。 */
    public enum TableEdit { INCLUDE, EXCLUDE, CLEAR }

    private final LootTrackingSettings settings;
    private final Set<LootTablePattern> builtins;
    private final Set<LootTablePattern> additional;
    private final Set<LootTablePattern> effective;
    private final Set<String> exclusions;

    public LootTrackingRules(LootTrackingSettings settings) {
        this.settings = settings;
        builtins = settings.followBuiltinRules() ? patterns(BUILTINS.stream()
                .filter(rule -> !settings.disabledBuiltinRules().contains(rule.id())).map(Builtin::selector).toList())
                : Set.of();
        additional = patterns(settings.additionalTrackingRules());
        Set<LootTablePattern> combined = new LinkedHashSet<>(builtins);
        combined.addAll(additional);
        effective = Set.copyOf(combined);
        exclusions = Set.copyOf(settings.excludedLootTables());
    }

    public Match match(ResourceLocation table) {
        if (exclusions.contains(table.toString())) return Match.EXCLUDED;
        boolean matchesBuiltin = matches(builtins, table);
        boolean matchesAdditional = matches(additional, table);
        if (matchesBuiltin && matchesAdditional) return Match.BOTH;
        if (matchesBuiltin) return Match.BUILTIN;
        return matchesAdditional ? Match.ADDITIONAL : Match.UNMATCHED;
    }

    public boolean isRootCandidate(ResourceLocation table) {
        return !exclusions.contains(table.toString()) && matches(effective, table);
    }

    public boolean hasExactOverride(ResourceLocation table) {
        return exclusions.contains(table.toString()) || additional.contains(
                new LootTablePattern(table.getNamespace(), table.getPath(), false));
    }

    /** 构造单表修改后的覆盖，调用者负责提交。 */
    public LootTrackingSettings edit(ResourceLocation table, TableEdit edit) {
        String id = table.toString();
        Set<String> rules = new LinkedHashSet<>(settings.additionalTrackingRules());
        Set<String> excludedTables = new LinkedHashSet<>(exclusions);
        switch (edit) {
            case INCLUDE -> {
                excludedTables.remove(id);
                if (!matches(effective, table)) rules.add(id);
            }
            case EXCLUDE -> excludedTables.add(id);
            case CLEAR -> {
                rules.remove(id);
                excludedTables.remove(id);
            }
        }
        return new LootTrackingSettings(settings.followBuiltinRules(), settings.disabledBuiltinRules(),
                List.copyOf(rules), List.copyOf(excludedTables), settings.signatureExcludedComponents(),
                settings.maxLogEntriesPerTable(), settings.trackingTimeoutTicks());
    }

    boolean affectsCatalogComparedTo(LootTrackingRules previous) {
        return !effective.equals(previous.effective) || !exclusions.equals(previous.exclusions)
                || !settings.signatureExclusions().equals(previous.settings.signatureExclusions());
    }

    private static Set<LootTablePattern> patterns(List<String> selectors) {
        Set<LootTablePattern> result = new LinkedHashSet<>();
        for (String selector : selectors) result.add(LootTablePattern.parse(selector));
        return Set.copyOf(result);
    }

    private static boolean matches(Set<LootTablePattern> patterns, ResourceLocation table) {
        for (LootTablePattern pattern : patterns) {
            if (pattern.matches(table)) return true;
        }
        return false;
    }
}
