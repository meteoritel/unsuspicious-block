package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 条件形态与三值结构折叠；叶子含义由实际消费者解释。 */
public final class ConditionTree {
    public static final ResourceLocation ALL = ResourceLocation.withDefaultNamespace("all_of");
    public static final ResourceLocation ANY = ResourceLocation.withDefaultNamespace("any_of");
    public static final ResourceLocation NOT = ResourceLocation.withDefaultNamespace("inverted");
    public enum Kind { ALL, ANY, NOT, LEAF }
    public enum Truth {
        TRUE, FALSE, UNKNOWN;
        public static Truth of(@Nullable Boolean value) {
            return value == null ? UNKNOWN : value ? TRUE : FALSE;
        }
        public @Nullable Boolean value() { return this == UNKNOWN ? null : this == TRUE; }
    }

    private ConditionTree() {}

    public static Kind kind(LootConditionInfo node) {
        return node.kind();
    }

    public static List<LootConditionInfo> logicChildren(LootConditionInfo node) {
        return node.logicChildren();
    }

    public static void walkLogic(LootConditionInfo node, Consumer<LootConditionInfo> visit) {
        visit.accept(node);
        logicChildren(node).forEach(child -> walkLogic(child, visit));
    }

    /** 结构遍历共用的代数；需求展开与三值求值分别拥有组合和预算语义。 */
    public interface Algebra<T> {
        T leaf(LootConditionInfo node, boolean negated);
        T identity(boolean conjunction);
        T combine(boolean conjunction, T left, T right);
        boolean isTerminal(boolean conjunction, T value);
        default boolean canVisit(LootConditionInfo node) { return true; }
        T unknown(LootConditionInfo node);
    }

    /** 极性由结构层传播，叶子解释保留随机支持集等领域语义。 */
    public static Truth fold(LootConditionInfo node, boolean negated,
                             BiFunction<LootConditionInfo, Boolean, Truth> leaf) {
        return fold(node, negated, new Algebra<>() {
            public Truth leaf(LootConditionInfo value, boolean polarity) { return leaf.apply(value, polarity); }
            public Truth identity(boolean conjunction) { return conjunction ? Truth.TRUE : Truth.FALSE; }
            public Truth combine(boolean conjunction, Truth left, Truth right) {
                if (isTerminal(conjunction, left)) return left;
                if (isTerminal(conjunction, right)) return right;
                return left == Truth.UNKNOWN || right == Truth.UNKNOWN ? Truth.UNKNOWN : identity(conjunction);
            }
            public boolean isTerminal(boolean conjunction, Truth value) {
                return value == (conjunction ? Truth.FALSE : Truth.TRUE);
            }
            public Truth unknown(LootConditionInfo value) { return Truth.UNKNOWN; }
        });
    }

    /** 空 ALL/ANY、NOT 极性、短路与访问预算共用同一结构遍历。 */
    public static <T> T fold(LootConditionInfo node, boolean negated, Algebra<T> algebra) {
        if (!algebra.canVisit(node)) return algebra.unknown(node);
        if (node.kind() == Kind.LEAF) return algebra.leaf(node, negated);
        if (node.kind() == Kind.NOT) return fold(node.logicChildren().getFirst(), !negated, algebra);
        boolean conjunction = (node.kind() == Kind.ALL) != negated;
        T result = algebra.identity(conjunction);
        for (LootConditionInfo child : node.logicChildren()) {
            if (algebra.isTerminal(conjunction, result)) break;
            result = algebra.combine(conjunction, result, fold(child, negated, algebra));
        }
        return result;
    }
}
