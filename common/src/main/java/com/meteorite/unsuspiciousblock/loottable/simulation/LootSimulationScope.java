package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;

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

    // 开启一个不可嵌套的模拟作用域；调用方必须用 try-with-resources 关闭
    public static Scope open(SimulationProfile profile) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("战利品模拟作用域不允许嵌套");
        }
        ACTIVE.set(new State(profile));
        return new Scope();
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
        return state.profile.conditionOutcome(condition);
    }

    private static final class State {
        private final SimulationProfile profile;

        private State(SimulationProfile profile) {
            this.profile = profile;
        }

    }

    /** 模拟作用域关闭句柄。 */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {
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
