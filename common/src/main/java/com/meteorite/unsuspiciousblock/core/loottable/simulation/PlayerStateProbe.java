package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩家主动触发的当前状态快照：读取手持工具、附魔、幸运以及当前所处世界条件并转为可勾选条件集合；
 * 缺失上下文的条件保持原勾选状态并逐项报告。
 */
public final class PlayerStateProbe {
    private static final Set<String> READABLE_SCENARIO_TYPES = Set.of(
            "location_check", "weather_check", "time_check", "entity_properties", "entity_scores");

    private PlayerStateProbe() {
    }

    /** 状态读取结果：标准化输入与供界面展示的补充提示。 */
    public record Result(SimulationInput input, List<Component> notes) {
    }

    /** 读取玩家当前环境与装备，填入目录允许范围内的合法输入。 */
    public static Result probe(ServerPlayer player, SimulationConstraintCatalog catalog, SimulationInput previous) {
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
            for (var holder : enchantments.keySet()) {
                if (holder.is(ResourceKey.create(Registries.ENCHANTMENT, entry.getKey()))) {
                    found = enchantments.getLevel(holder);
                }
            }
            if (found > entry.getValue()) {
                notes.add(note("probe_level", entry.getKey().toString()));
            } else {
                levels.put(entry.getKey(), found);
            }
        }
        float luck = player.getLuck();
        if (luck < -5 || luck > 10 || !Float.isFinite(luck)) {
            luck = previous.params().luck();
            notes.add(note("probe_luck"));
        }
        Entity entity = !catalog.baseProfile().fishingContext() ? player : player.fishing;
        LootContext context = LootContextParamFiller.createConditionTestContext(
                player.serverLevel(), player.position(), held, entity, luck);

        Set<String> previousEnabled = Set.copyOf(previous.enabledConditions());
        Set<String> desired = new LinkedHashSet<>();
        for (ConditionOption option : catalog.conditions()) {
            LootConditionInfo info = option.condition();
            var source = info.source();
            String type = info.conditionType().getPath();
            boolean readable = READABLE_SCENARIO_TYPES.contains(type)
                    && source != null
                    && source.getReferencedContextParams().stream().allMatch(context::hasParam);
            if (!readable) {
                if (previousEnabled.contains(option.id())) {
                    desired.add(option.id());
                }
                notes.add(note("probe_missing", info.description()));
                continue;
            }
            try {
                if (source.test(context)) {
                    desired.add(option.id());
                }
            } catch (RuntimeException ignored) {
                if (previousEnabled.contains(option.id())) {
                    desired.add(option.id());
                }
                notes.add(note("probe_missing", info.description()));
            }
        }
        var params = new ScenarioParams(luck, tool, levels, previous.params().sampleCount());
        return new Result(catalog.resolve(List.copyOf(desired), params).orElse(previous), List.copyOf(notes));
    }

    private static Component note(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key, args);
    }
}
