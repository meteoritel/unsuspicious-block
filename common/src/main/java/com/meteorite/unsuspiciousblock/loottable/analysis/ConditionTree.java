package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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
        public Truth negate(boolean negated) {
            return !negated || this == UNKNOWN ? this : this == TRUE ? FALSE : TRUE;
        }
    }

    private ConditionTree() {}

    public static Kind kind(LootConditionInfo node) {
        ResourceLocation id = node.conditionType();
        if (ALL.equals(id)) return Kind.ALL;
        if (ANY.equals(id)) return Kind.ANY;
        if (NOT.equals(id)) return Kind.NOT;
        return Kind.LEAF;
    }

    public static List<LootConditionInfo> logicChildren(LootConditionInfo node) {
        return kind(node) == Kind.LEAF ? List.of() : node.children();
    }

    public static List<LootConditionInfo> displayChildren(LootConditionInfo node) { return node.children(); }

    public static void walkLogic(LootConditionInfo node, Consumer<LootConditionInfo> visit) {
        visit.accept(node);
        logicChildren(node).forEach(child -> walkLogic(child, visit));
    }

    public static Truth evaluate(LootConditionInfo node, Function<LootConditionInfo, Truth> leaf) {
        return fold(node, false, (value, negated) -> leaf.apply(value).negate(negated));
    }

    // polarity 传给叶子，允许推荐器使用随机条件的正/反支持集而非简单反转概率。
    public static Truth fold(LootConditionInfo node, boolean negated,
                             BiFunction<LootConditionInfo, Boolean, Truth> leaf) {
        Kind kind = kind(node);
        List<LootConditionInfo> children = logicChildren(node);
        if (kind == Kind.LEAF) return leaf.apply(node, negated);
        if (kind == Kind.NOT) {
            return children.size() == 1 ? fold(children.getFirst(), !negated, leaf) : Truth.UNKNOWN;
        }
        boolean conjunction = (kind == Kind.ALL) != negated;
        boolean unknown = false;
        for (LootConditionInfo child : children) {
            Truth value = fold(child, negated, leaf);
            if (value == Truth.UNKNOWN) unknown = true;
            else if ((value == Truth.TRUE) != conjunction) return conjunction ? Truth.FALSE : Truth.TRUE;
        }
        return unknown ? Truth.UNKNOWN : conjunction ? Truth.TRUE : Truth.FALSE;
    }
}
