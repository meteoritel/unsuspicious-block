package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 战利品模拟的线程内作用域，只在主线程执行模拟抽取期间暴露当前 profile。
 * Mixin 仅通过本类查询条件覆盖结果，所有场景策略都保留在普通 Java 代码中。
 */
public final class LootSimulationScope {
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

    private LootSimulationScope() {
    }

    // 供平台注入层跳过真实玩家追踪等模拟期副作用
    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    // 仅直接子表需要包装产物 Consumer；更深层产物会自然经过直接子表的外层 Consumer。
    public static boolean shouldObserveChildTable(ResourceLocation tableId) {
        State state = ACTIVE.get();
        return state != null && state.directChildTables.contains(tableId);
    }

    // 开始一次独立抽取，清空上一次抽取记录的子表产出。
    public static void beginRoll() {
        State state = ACTIVE.get();
        if (state != null) {
            state.childTablesWithDrops.clear();
            state.childSources.clear();
        }
    }

    // 记录本次抽取中实际产出物品的直接子表。
    public static void recordChildTableDrop(ResourceLocation tableId, ItemStack stack) {
        State state = ACTIVE.get();
        if (state != null && !stack.isEmpty() && state.directChildTables.contains(tableId)) {
            if (!state.childTablesWithDrops.contains(tableId)) {
                state.childTablesWithDrops.add(tableId);
            }
            state.childSources.put(stack, tableId);
        }
    }

    // 同步遍历本轮实际产出物品的直接子表，不复制内部集合。
    public static void forEachChildTableWithDrops(Consumer<ResourceLocation> consumer) {
        State state = ACTIVE.get();
        if (state == null) {
            return;
        }
        for (int index = 0; index < state.childTablesWithDrops.size(); index++) {
            consumer.accept(state.childTablesWithDrops.get(index));
        }
    }

    // 若 stack 由当前 roll 的某个直接子表产出，优先按对象身份定位，必要时按组件兼容回退。
    @Nullable
    public static ResourceLocation sourceChildTable(ItemStack stack) {
        State state = ACTIVE.get();
        if (state == null) {
            return null;
        }
        ResourceLocation source = state.childSources.get(stack);
        if (source != null) {
            return source;
        }
        for (Map.Entry<ItemStack, ResourceLocation> entry : state.childSources.entrySet()) {
            if (ItemStack.isSameItemSameComponents(entry.getKey(), stack)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // 开启一个不可嵌套的模拟作用域；调用方必须用 try-with-resources 关闭
    public static Scope open(SimulationProfile profile, Set<ResourceLocation> directChildTables) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("战利品模拟作用域不允许嵌套");
        }
        State state = new State(profile, directChildTables);
        ACTIVE.set(state);
        return new Scope(state);
    }

    /**
     * 返回当前模拟对条件的覆盖结果；null 表示继续执行原版判断。
     */
    @Nullable
    public static Boolean overrideResult(LootItemCondition condition) {
        State state = ACTIVE.get();
        if (state == null) {
            return null;
        }
        if (condition instanceof InvertedLootItemCondition
                || condition instanceof CompositeLootItemCondition) {
            return null;
        }
        if (state.conditionOutcomes.containsKey(condition)) {
            return state.conditionOutcomes.get(condition);
        }
        Boolean result = state.profile.conditionOutcome(condition);
        if (result == null) {
            state.recordUncovered(condition);
        }
        state.conditionOutcomes.put(condition, result);
        return result;
    }

    private static final class State {
        private final SimulationProfile profile;
        private final Set<ResourceLocation> directChildTables;
        private final List<ResourceLocation> childTablesWithDrops = new ArrayList<>();
        private final IdentityHashMap<ItemStack, ResourceLocation> childSources = new IdentityHashMap<>();
        private final IdentityHashMap<LootItemCondition, Boolean> conditionOutcomes = new IdentityHashMap<>();
        private final Set<String> uncoveredConditions = new LinkedHashSet<>();

        private State(SimulationProfile profile, Set<ResourceLocation> directChildTables) {
            this.profile = profile;
            this.directChildTables = directChildTables;
        }

        // 记录"属于场景控制类型、却未被任何代表场景覆盖"的条件，供上层汇总告警。
        // 这类条件只能按真实逻辑求值，数值可能因此偏离场景估算；不记录非场景控制类型
        // （它们本就不由场景接管，未被覆盖是预期行为）。
        private void recordUncovered(LootItemCondition condition) {
            ResourceLocation conditionId =
                    BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(condition.getType());
            if (conditionId == null || !SimulationScenarioPlanner.isScenarioControlled(conditionId)) {
                return;
            }
            this.uncoveredConditions.add(conditionId + " (" + condition.getClass().getSimpleName() + ")");
        }
    }

    /** 模拟作用域关闭句柄。 */
    public static final class Scope implements AutoCloseable {
        private final State state;
        private boolean closed;

        private Scope(State state) {
            this.state = state;
        }

        /** 当前作用域内未被代表场景覆盖的场景控制类型条件；须在 {@link #close()} 前读取。 */
        public Set<String> uncoveredConditions() {
            return Set.copyOf(this.state.uncoveredConditions);
        }

        @Override
        public void close() {
            if (!this.closed) {
                ACTIVE.remove();
                this.closed = true;
            }
        }
    }
}
