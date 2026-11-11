package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import java.util.*;

/** 玩家主动触发的状态快照；缺失上下文保持原值，并逐项报告。 */
public final class PlayerStateProbe {
    private PlayerStateProbe() {}
    public record Result(SimulationInput input, List<Component> notes) {}

    public static Result probe(ServerPlayer player, TableDefinition table, SimulationConstraintCatalog catalog,
                                SimulationInput previous) {
        SimulationScenario previousScene = catalog.scenario(previous.scenarioKey());
        if (previousScene == null) return new Result(previous, List.of(note("probe_scene")));
        List<Component> notes = new ArrayList<>();
        var held = player.getMainHandItem();
        ResourceLocation tool = BuiltInRegistries.ITEM.getKey(held.getItem());
        if (catalog.tool(tool) == null) {
            tool = previous.params().toolId();
            notes.add(note("probe_tool"));
        }
        Map<ResourceLocation, Integer> levels = new LinkedHashMap<>(previous.params().toolEnchantments());
        var enchantments = held.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : catalog.enchantmentMaxLevels().entrySet()) {
            int found = 0;
            for (var holder : enchantments.keySet())
                if (holder.is(ResourceKey.create(
                        Registries.ENCHANTMENT, entry.getKey())))
                    found = enchantments.getLevel(holder);
            if (found > entry.getValue()) notes.add(note("probe_level", entry.getKey().toString()));
            else levels.put(entry.getKey(), found);
        }
        float luck = player.getLuck();
        if (luck < -5 || luck > 10 || !Float.isFinite(luck)) {
            luck = previous.params().luck();
            notes.add(note("probe_luck"));
        }
        var builder = new LootParams.Builder(player.serverLevel())
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withParameter(LootContextParams.TOOL, held).withLuck(luck);
        boolean fishing = SimulationProfile.eligibleConditions(player.serverLevel(), table.type()).fishingContext();
        if (!fishing) builder.withParameter(LootContextParams.THIS_ENTITY, player);
        else if (player.fishing != null) builder.withParameter(LootContextParams.THIS_ENTITY, player.fishing);
        LootContext context = new LootContext.Builder(builder.create(LootContextParamSets.ALL_PARAMS)).create(Optional.empty());
        Map<ConditionIdentity, LootConditionInfo> conditions = new LinkedHashMap<>();
        for (var item : table.items()) for (var path : item.acquisitionPaths())
            for (var condition : path.allConditions()) collect(condition, conditions);
        Map<ConditionIdentity, Boolean> desired = new LinkedHashMap<>(previousScene.profile().conditionOutcomes());
        for (var entry : conditions.entrySet()) {
            var info = entry.getValue();
            var source = info.source();
            String type = info.conditionType().getPath();
            boolean readable = Set.of("location_check", "weather_check", "time_check",
                    "entity_properties", "entity_scores").contains(type)
                    && source != null && source.getReferencedContextParams().stream().allMatch(context::hasParam);
            if (!readable) { notes.add(note("probe_missing", info.description())); continue; }
            try { desired.put(entry.getKey(), source.test(context)); }
            catch (RuntimeException ignored) { notes.add(note("probe_missing", info.description())); }
        }
        String scene = previous.scenarioKey();
        Optional<SimulationScenario> match = catalog.scenarios().stream()
                .filter(s -> s.profile().conditionOutcomes().equals(desired)).findFirst();
        if (match.isPresent()) scene = match.get().key();
        else notes.add(note("probe_scene"));
        var params = new ScenarioParams(luck, tool, levels, previous.params().sampleCount());
        return new Result(catalog.resolve(scene, params).orElse(previous), List.copyOf(notes));
    }

    private static void collect(LootConditionInfo info, Map<ConditionIdentity, LootConditionInfo> result) {
        ConditionTree.walkLogic(info, node -> {
            if (ConditionTree.kind(node) == ConditionTree.Kind.LEAF
                    && SimulationScenarioPlanner.isScenarioControlled(node.conditionType())) {
                result.putIfAbsent(ConditionIdentity.of(node), node);
            }
        });
    }

    private static Component note(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key, args);
    }
}

