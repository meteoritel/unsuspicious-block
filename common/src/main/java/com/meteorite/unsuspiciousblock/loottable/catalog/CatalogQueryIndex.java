package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 目录查询索引——把"子树内物品"这类跨表聚合从散落的递归函数收敛到一处，并固定数据来源优先级。
 * <p>
 * 服务端按 generation 构建：拓扑取自引用图，物品来源为"模拟态优先、缺失处回退静态投影"。
 * 于是查询在模拟进行中也能得到完整结果——尚未模拟的表回退到静态投影，已模拟的表用带概率的
 * 模拟结果（其物品集合是静态投影的超集，含模拟期发现的注入条目）。这消除了"传哪张 map 就
 * 得到哪套语义"的隐式约定。
 * <p>
 * 客户端只有同步到的表定义、没有引用图，用 {@link #subtreeItems(Map, ResourceLocation)} 这个
 * 等价入口；两者共用同一份遍历实现，避免聚合规则漂移。
 * <p>
 * "是否在目录中"沿用既有口径：只有真正产出物品的表才参与聚合，也因此只有它们才能作为子表
 * 入口——不在目录中的表既不收集物品、也不再向下遍历。
 */
public final class CatalogQueryIndex {
    /** 空索引——目录尚未加载时的安全缺省，查询一律返回空结果。 */
    public static final CatalogQueryIndex EMPTY = new CatalogQueryIndex(
            emptyGraph(), Map.of(), Map::of);

    private final LootTableReferenceGraph referenceGraph;
    private final Map<ResourceLocation, StaticTableProjection> staticProjections;
    private final Set<ResourceLocation> directoryTables;
    private final Supplier<Map<ResourceLocation, TableDefinition>> simulatedTables;

    /**
     * @param referenceGraph     本代引用图（拓扑）
     * @param staticProjections  本代全部追踪表的静态投影
     * @param simulatedTables    本代模拟结果视图；未模拟的表由此缺失并回退静态投影
     */
    public CatalogQueryIndex(LootTableReferenceGraph referenceGraph,
                             Map<ResourceLocation, StaticTableProjection> staticProjections,
                             Supplier<Map<ResourceLocation, TableDefinition>> simulatedTables) {
        this.referenceGraph = referenceGraph;
        this.staticProjections = Map.copyOf(staticProjections);
        this.simulatedTables = simulatedTables;

        Set<ResourceLocation> tables = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, StaticTableProjection> entry : staticProjections.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                tables.add(entry.getKey());
            }
        }
        this.directoryTables = Set.copyOf(tables);
    }

    /**
     * 指定表及其全部后代表中的唯一物品，按深度优先顺序保留首次出现的签名。
     * 共享子表只聚合一次；不在目录中的表既不收集也不再向下遍历。
     */
    public List<ItemDefinition> subtreeItems(ResourceLocation rootTableId) {
        return collect(rootTableId,
                this.directoryTables::contains,
                this::itemsOf,
                this.referenceGraph::directChildren);
    }

    /**
     * 只有同步目录表定义时的等价入口（客户端场景）。
     * 遍历、去重与"不在目录中即跳过"的口径与服务端一致。
     */
    public static List<ItemDefinition> subtreeItems(Map<ResourceLocation, TableDefinition> catalog,
                                                    ResourceLocation rootTableId) {
        return collect(rootTableId,
                catalog::containsKey,
                tableId -> {
                    TableDefinition table = catalog.get(tableId);
                    return table == null ? List.of() : table.items();
                },
                tableId -> {
                    TableDefinition table = catalog.get(tableId);
                    return table == null ? List.of() : table.childTables();
                });
    }

    // 该表的有效物品：已模拟用模拟结果，未模拟回退静态投影，不在目录中则为空
    private List<ItemDefinition> itemsOf(ResourceLocation tableId) {
        TableDefinition simulated = this.simulatedTables.get().get(tableId);
        if (simulated != null) {
            return simulated.items();
        }
        StaticTableProjection projection = this.staticProjections.get(tableId);
        return projection == null ? List.of() : projection.items();
    }

    // 共用遍历：深度优先前序、共享节点只访问一次、按签名去重且首次出现者胜
    private static List<ItemDefinition> collect(
            ResourceLocation rootTableId, Predicate<ResourceLocation> inDirectory,
            Function<ResourceLocation, List<ItemDefinition>> items,
            Function<ResourceLocation, List<ResourceLocation>> children) {
        Map<LootResultSignature, ItemDefinition> itemsBySignature = new LinkedHashMap<>();
        collect(rootTableId, inDirectory, items, children, itemsBySignature, new LinkedHashSet<>());
        return List.copyOf(itemsBySignature.values());
    }

    private static void collect(
            ResourceLocation tableId, Predicate<ResourceLocation> inDirectory,
            Function<ResourceLocation, List<ItemDefinition>> items,
            Function<ResourceLocation, List<ResourceLocation>> children,
            Map<LootResultSignature, ItemDefinition> output, Set<ResourceLocation> visited) {
        if (!visited.add(tableId) || !inDirectory.test(tableId)) {
            return;
        }
        for (ItemDefinition item : items.apply(tableId)) {
            output.putIfAbsent(item.signature(), item);
        }
        for (ResourceLocation childId : children.apply(tableId)) {
            collect(childId, inDirectory, items, children, output, visited);
        }
    }

    private static LootTableReferenceGraph emptyGraph() {
        LootTableSourceSnapshot emptySnapshot =
                LootTableSourceSnapshot.capture(ResourceManager.Empty.INSTANCE);
        return LootTableReferenceGraph.build(emptySnapshot, Map.of());
    }
}
