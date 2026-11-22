package com.meteorite.unsuspiciousblock.core.loottable;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTablePattern;
import com.meteorite.unsuspiciousblock.core.loottable.graph.RuntimeLootLinks;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 从世界覆盖解析收录根表规则，目录负责沿引用收录子表。 */
public final class LootTrackingRules {
    /** 稳定标识独立于选择器，世界可以持续禁用同一内置规则。 */
    public record Builtin(String id, String selector) {}

    /** 命中的已启用选择器以及该表的精确覆盖。 */
    public record Explanation(Match match, List<String> selectors, boolean exactOverride) {}

    private record Rule(String label, LootTablePattern pattern) {}

    public static final List<Builtin> BUILTINS = List.of(
            new Builtin("archaeology", "archaeology/"),
            new Builtin("archeology", "archeology/"),
            new Builtin("pots", "pots/"),
            new Builtin("fishing", RuntimeLootLinks.FISHING_TABLE.toString()),
            new Builtin("buried_treasure", "minecraft:chests/buried_treasure"),
            new Builtin("ancient_city", "minecraft:chests/ancient_city"),
            new Builtin("ancient_city_ice_box", "minecraft:chests/ancient_city_ice_box"),
            new Builtin("mod_fishing", RuntimeLootLinks.MUD_DREDGING_TABLE.toString()),
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
    private final List<Rule> builtins;
    private final List<Rule> additional;
    private final Set<LootTablePattern> effective;
    private final Set<String> exclusions;

    public LootTrackingRules(LootTrackingSettings settings) {
        this.settings = settings;
        builtins = settings.followBuiltinRules() ? BUILTINS.stream()
                .filter(rule -> !settings.disabledBuiltinRules().contains(rule.id()))
                .map(rule -> toRule(rule.selector())).toList() : List.of();
        additional = settings.additionalTrackingRules().stream().map(LootTrackingRules::toRule).toList();
        Set<LootTablePattern> combined = new LinkedHashSet<>();
        builtins.forEach(rule -> combined.add(rule.pattern()));
        additional.forEach(rule -> combined.add(rule.pattern()));
        effective = Set.copyOf(combined);
        exclusions = Set.copyOf(settings.excludedLootTables());
    }

    private static Match match(boolean matchesBuiltin, boolean matchesAdditional) {
        if (matchesBuiltin && matchesAdditional) return Match.BOTH;
        if (matchesBuiltin) return Match.BUILTIN;
        return matchesAdditional ? Match.ADDITIONAL : Match.UNMATCHED;
    }

    public boolean isRootCandidate(ResourceLocation table) {
        return !exclusions.contains(table.toString()) && matches(effective, table);
    }

    /** 返回收录判定用到的规则文本。精确排除仍保留被覆盖的命中来源。 */
    public Explanation explain(ResourceLocation table) {
        List<String> selectors = new ArrayList<>();
        collectMatches(builtins, table, selectors);
        boolean hasBuiltin = !selectors.isEmpty();
        int builtinCount = selectors.size();
        collectMatches(additional, table, selectors);
        Match match = exclusions.contains(table.toString()) ? Match.EXCLUDED
                : match(hasBuiltin, selectors.size() > builtinCount);
        return new Explanation(match, selectors.stream().distinct().toList(), hasExactOverride(table));
    }

    /** 找出当前没有匹配任何可分析表的选择器。 */
    public List<String> unmatchedSelectors(Collection<ResourceLocation> tables) {
        return additional.stream().filter(rule -> tables.stream().noneMatch(rule.pattern()::matches))
                .map(Rule::label).toList();
    }

    public boolean hasExactOverride(ResourceLocation table) {
        return exclusions.contains(table.toString()) || settings.additionalTrackingRules().contains(table.toString());
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

    private static Rule toRule(String selector) {
        return new Rule(selector, LootTablePattern.parse(selector));
    }

    private static void collectMatches(List<Rule> rules, ResourceLocation table, List<String> selectors) {
        for (Rule rule : rules) {
            if (rule.pattern().matches(table)) selectors.add(rule.label());
        }
    }

    private static boolean matches(Set<LootTablePattern> patterns, ResourceLocation table) {
        for (LootTablePattern pattern : patterns) {
            if (pattern.matches(table)) return true;
        }
        return false;
    }
}
