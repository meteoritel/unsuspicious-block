package com.meteorite.unsuspiciousblock.loottable.graph;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootParseUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 平台运行时联动的单一权威——把"两张表之间的注入关系"与"模拟上下文类型判定"
 * 收敛到同一处声明，取代散落在目录加载器、场景规划器、条件类、客户端视图模型与
 * Mixin 里的字面量。
 * <p>
 * 这里只声明标识符与意图；合成边是否真的进入引用图，由建图方按"两端资源都存在"的
 * 守卫决定（见 {@link LootTableReferenceGraph#build}），避免图里出现快照中不存在的节点。
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

    private RuntimeLootLinks() {
    }

    /**
     * 平台注入关系：来源表 -&gt; 合成边。
     * 返回的是未套用存在性守卫的原始声明，建图时按快照过滤。
     */
    public static Map<ResourceLocation, List<LootTableEdge>> syntheticEdges() {
        return Map.of(FISHING_TABLE, List.of(LootTableEdge.runtimeInjection(MUD_DREDGING_TABLE)));
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
