package com.meteorite.unsuspiciousblock.loottable.graph;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootParseUtil;
import com.meteorite.unsuspiciousblock.loottable.condition.ToolEnchantmentCondition;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 平台运行时联动的单一权威——把"两张表之间的注入关系"与"模拟上下文类型判定"
 * 收敛到同一处声明，取代散落在目录加载器、场景规划器、条件类、客户端视图模型与
 * Mixin 里的字面量。
 * <p>
 * 这里只声明标识符与意图；合成边是否真的进入引用图，由建图方按"两端资源都存在"的
 * 守卫决定（见 {@link LootTableReferenceGraph#build}），避免图里出现快照中不存在的节点。
 * <p>
 * 注入边的**入口门槛**也声明在这里（见 {@link InjectionEdge}）：它对玩法、每表哈希、附魔等级旋钮
 * 与父表页的子表入口说明都是同一份事实，因此只有一个来源。
 */
public final class RuntimeLootLinks {
    /** 原版钓鱼表——Fabric 加载期注入池与 NeoForge GLM 的挂载点。 */
    public static final ResourceLocation FISHING_TABLE =
            ResourceLocation.withDefaultNamespace("gameplay/fishing");

    /** 泥地打捞子表——由平台注入进钓鱼表，原始 JSON 中看不到这条引用。 */
    public static final ResourceLocation MUD_DREDGING_TABLE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging");

    /** 泥地打捞子表的 {@link ResourceKey} 形态——平台注入与掉落追踪共用的表身份。 */
    public static final ResourceKey<LootTable> MUD_DREDGING_TABLE_KEY =
            ResourceKey.create(Registries.LOOT_TABLE, MUD_DREDGING_TABLE);

    /** 原版与模组钓鱼表声明的战利品表类型，表示模拟需要钓鱼上下文（默认钓竿、假浮标）。 */
    private static final String FISHING_DECLARED_TYPE = "fishing";

    /** 泥地打捞注入边的门槛概率曲线：基础 20%，每高一级 +10%。 */
    private static final LevelBasedValue MUD_DREDGING_CHANCE = new LevelBasedValue.Linear(0.2F, 0.1F);

    /** 泥地打捞注入边的门槛：工具带泥底打捞、至少 I 级、按实际等级掷一次概率。 */
    public static final InjectionGate MUD_DREDGING_GATE = new InjectionGate(
            ModEnchantments.MUD_DREDGING.location(), ToolEnchantmentCondition.DEFAULT_MIN_LEVEL,
            Optional.of(MUD_DREDGING_CHANCE));

    /**
     * 平台注入关系的**唯一声明**。
     * <p>
     * 引用图（{@link #syntheticEdges()}）、门槛判定（两端的注入实现）、每表哈希与附魔等级旋钮、
     * 以及父表页子表入口的"需要什么条件"，全部从这一个列表派生——不另立副本，
     * 因为"同一条边写两遍"的后果是它们会各自漂移，而漂移的症状（图里有边但门槛不判、
     * 或 tooltip 说的和玩法不同）都很难从现象反推成因。
     */
    private static final List<InjectionEdge> INJECTION_EDGES = List.of(
            new InjectionEdge(FISHING_TABLE, MUD_DREDGING_TABLE, MUD_DREDGING_GATE));

    private RuntimeLootLinks() {
    }

    /**
     * 一条平台注入边：{@code source} 在运行时把 {@code target} 注入自己的产出，
     * 并由 {@code gate} 决定"进不进得去"。
     */
    public record InjectionEdge(ResourceLocation source, ResourceLocation target, InjectionGate gate) {
        public InjectionEdge {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(gate, "gate");
        }
    }

    /**
     * 注入边的**入口门槛**——附魔 + 最低等级 + 按等级变化的概率曲线。
     * <p>
     * 被注入子表自己的 JSON 里**不写**这条条件：它由注入处承载（Fabric 追加的父表 pool 条件、
     * NeoForge GLM 的代码判定），因此子表在自己的页面里按"已进入本表"展示内容分布，
     * 而父表页的子表入口按这条门槛回答"能不能进本表"。
     * <p>
     * 两端都从本声明构造条件，所以"玩法"与"tooltip 上写的话"不可能各说一套；
     * 附魔身份也只有 {@link ModEnchantments#MUD_DREDGING} 一个字面量来源。
     */
    public record InjectionGate(ResourceLocation enchantmentId, int minLevel,
                                Optional<LevelBasedValue> chance) {
        public InjectionGate {
            Objects.requireNonNull(enchantmentId, "enchantmentId");
            Objects.requireNonNull(chance, "chance");
            if (minLevel < ToolEnchantmentCondition.DEFAULT_MIN_LEVEL) {
                throw new IllegalArgumentException("注入门槛的最低等级不可低于 "
                        + ToolEnchantmentCondition.DEFAULT_MIN_LEVEL);
            }
            chance = chance.map(Objects::requireNonNull);
        }

        /** 构造该门槛实际使用的条件；注册表里缺这个附魔时抛错（与数据包改坏表层同型）。 */
        public ToolEnchantmentCondition condition(HolderLookup.Provider registries) {
            Holder<Enchantment> holder = registries.lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, this.enchantmentId));
            return new ToolEnchantmentCondition(holder, this.minLevel, this.chance);
        }
    }

    /**
     * 平台注入关系：来源表 -&gt; 合成边。
     * 返回的是未套用存在性守卫的原始声明，建图时按快照过滤。
     */
    public static Map<ResourceLocation, List<LootTableEdge>> syntheticEdges() {
        Map<ResourceLocation, List<LootTableEdge>> edges = new LinkedHashMap<>();
        for (InjectionEdge edge : INJECTION_EDGES) {
            edges.computeIfAbsent(edge.source(), ignored -> new ArrayList<>())
                    .add(LootTableEdge.runtimeInjection(edge.target()));
        }
        return Map.copyOf(edges);
    }

    /**
     * 该表作为**被注入子表**时的入口门槛；没有注入边时为空。
     * <p>
     * 父表页的子表入口据此说明"需要什么条件"——这条边不在任何 JSON 里，静态投影看不到它，
     * 不从这里取就只能显示一个没有原因的「未命中」。
     */
    public static Optional<InjectionGate> injectionGate(ResourceLocation targetTableId) {
        for (InjectionEdge edge : INJECTION_EDGES) {
            if (edge.target().equals(targetTableId)) {
                return Optional.of(edge.gate());
            }
        }
        return Optional.empty();
    }

    /**
     * 该表**发起注入**时，其各条边的门槛附魔；该表没有注入边时返回空集。
     * <p>
     * 记在发起注入的一方而不是被注入的子表，是因为"要带几级附魔才进得去"改变的是**父表**能产出什么
     * （注入场景带满级工具）与父表的旋钮清单；子表自己已经不含这条条件，给它挂旋钮只会多出一个
     * 点了没反应的控件。每表哈希据此把该附魔的 {@code max_level} 计入摘要，否则改 {@code max_level}
     * 不会让任何表失效。
     */
    public static Set<ResourceLocation> injectionGateEnchantments(ResourceLocation sourceTableId) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (InjectionEdge edge : INJECTION_EDGES) {
            if (edge.source().equals(sourceTableId)) {
                result.add(edge.gate().enchantmentId());
            }
        }
        return Set.copyOf(result);
    }

    /** 模拟上下文——决定默认工具与 THIS_ENTITY 的填充方式。 */
    public enum SimulationContext {
        /** 钓鱼上下文：默认钓竿，THIS_ENTITY 填假浮标。 */
        FISHING,
        /** 通用上下文：默认钻石镐，THIS_ENTITY 填假玩家。 */
        GENERIC
    }

    /**
     * 按战利品表自己声明的 {@code type} 判定模拟上下文。
     * <p>
     * 原版与模组的钓鱼族战利品表均在 JSON 顶层声明 {@code "type": "minecraft:fishing"}，
     * 因此读声明类型比按表 id 的 path 猜测更准确：命名里带 fishing 的非钓鱼表不再被误判。
     * 归一化复用 {@link LootParseUtil#normalizeType}，`minecraft:fishing` 与 `fishing` 等价；
     * 缺失或无法识别的声明退回通用上下文。
     */
    public static SimulationContext contextKind(@Nullable String declaredType) {
        return FISHING_DECLARED_TYPE.equals(LootParseUtil.normalizeType(declaredType))
                ? SimulationContext.FISHING
                : SimulationContext.GENERIC;
    }
}
