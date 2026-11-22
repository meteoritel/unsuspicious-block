package com.meteorite.unsuspiciousblock.core.loottable.graph;

import com.meteorite.unsuspiciousblock.core.loottable.source.LootTableSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战利品表引用关系图——引用关系的唯一权威：直接子表、可达集、子树（含自身）、
 * 强连通环集合。解析器、目录加载器与哈希计算共用这份拓扑。
 * <p>
 * 邻接表按目标表去重并保持出现顺序（JSON 引用在前、合成边在后）；但需要保留每个
 * 引用出现位置语义的场合（同一子表在多处的 conditions / functions 不同）由编译层
 * 独立保序，不依赖本图——图只保证"有哪些边"，不保证"引用了几次"。
 */
public final class LootTableReferenceGraph {
    private final LootTableSourceSnapshot snapshot;
    private final Map<ResourceLocation, List<LootTableEdge>> syntheticEdges;
    private final Map<ResourceLocation, List<LootTableEdge>> adjacency = new HashMap<>();

    private LootTableReferenceGraph(LootTableSourceSnapshot snapshot,
                                    Map<ResourceLocation, List<LootTableEdge>> syntheticEdges) {
        this.snapshot = snapshot;
        this.syntheticEdges = syntheticEdges;
    }

    /**
     * 绑定生效资源与平台声明。服务端主线程沿收录路径读取并缓存出边。
     */
    public static LootTableReferenceGraph build(LootTableSourceSnapshot snapshot,
                                                Map<ResourceLocation, List<LootTableEdge>> syntheticEdges) {
        return new LootTableReferenceGraph(snapshot, syntheticEdges);
    }

    /** 图中的全部节点（等于快照中的表，含无引用的叶子表）。 */
    public Set<ResourceLocation> nodes() {
        return this.snapshot.tableIds();
    }

    /** 该表是否在图中（即有资源）。 */
    public boolean contains(ResourceLocation tableId) {
        return nodes().contains(tableId);
    }

    /** 该表的全部出边（含目标缺失的悬空边，供诊断与告警使用）。 */
    public List<LootTableEdge> directEdges(ResourceLocation tableId) {
        return contains(tableId) ? this.adjacency.computeIfAbsent(tableId, this::readEdges) : List.of();
    }

    private List<LootTableEdge> readEdges(ResourceLocation tableId) {
        Set<ResourceLocation> references = this.snapshot.directReferences(tableId);
        List<LootTableEdge> edges = new ArrayList<>();
        references.forEach(target -> edges.add(LootTableEdge.jsonReference(target)));
        Set<ResourceLocation> seen = new LinkedHashSet<>(references);
        for (LootTableEdge edge : this.syntheticEdges.getOrDefault(tableId, List.of())) {
            if (contains(edge.target()) && seen.add(edge.target())) {
                edges.add(edge);
            }
        }
        return List.copyOf(edges);
    }

    /**
     * 该表的直接子表 id——只返回快照中真实存在的目标，去重且保持出现顺序。
     * <p>
     * 注意这里不判断"子表是否有物品"：无物品的表是否作为目录子表入口，由投影层按
     * 不变量 #12 过滤，图保持纯拓扑。
     */
    public List<ResourceLocation> directChildren(ResourceLocation tableId) {
        List<ResourceLocation> children = new ArrayList<>();
        for (LootTableEdge edge : directEdges(tableId)) {
            if (contains(edge.target())) {
                children.add(edge.target());
            }
        }
        return List.copyOf(children);
    }

    /**
     * 从给定根集合出发的可达集合（含根自身），保持广度优先发现顺序。
     * 根或目标不在图中时跳过——这与今天"引用图里没有该节点就不再展开"的行为一致。
     */
    public Set<ResourceLocation> reachableFrom(Set<ResourceLocation> roots) {
        return reachableFrom(roots, Set.of());
    }

    /** 带排除集的可达集合：排除集中的节点既不进入结果也不再展开。 */
    public Set<ResourceLocation> reachableFrom(Set<ResourceLocation> roots, Set<ResourceLocation> excluded) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        ArrayList<ResourceLocation> queue = new ArrayList<>(roots);
        for (int index = 0; index < queue.size(); index++) {
            ResourceLocation current = queue.get(index);
            if (excluded.contains(current) || !contains(current) || !result.add(current)) {
                continue;
            }
            for (ResourceLocation child : directChildren(current)) {
                if (!excluded.contains(child)) {
                    queue.add(child);
                }
            }
        }
        return result;
    }

    /**
     * 以该表为根的子树（含自身），深度优先前序、共享节点只访问一次、稳定顺序。
     * 根不在图中时返回空集合。
     */
    public Set<ResourceLocation> descendantsInclusive(ResourceLocation rootId) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        collectDescendants(rootId, result);
        return result;
    }

    private void collectDescendants(ResourceLocation tableId, Set<ResourceLocation> output) {
        if (!contains(tableId) || !output.add(tableId)) {
            return;
        }
        for (ResourceLocation child : directChildren(tableId)) {
            collectDescendants(child, output);
        }
    }

    /**
     * scope 内的循环分量列表，每个分量是一个环上的节点集合（稳定顺序）。
     * <p>
     * 分量即强连通分量（SCC）：非平凡分量（成员数 &gt; 1）与带自环的单点分量都算循环。
     * 供调用方按分量输出一次性日志、并把分量成员合并为排除集，避免同一环重复刷屏。
     * SCC 可全局计算，但排除与日志策略只作用于调用方给定的 scope（追踪根的初始可达集），
     * 与考古目录无关的第三方表循环不得新增告警。
     */
    public List<Set<ResourceLocation>> cyclicComponentsIn(Set<ResourceLocation> scope) {
        Map<ResourceLocation, Integer> indexByNode = new HashMap<>();
        Map<ResourceLocation, Integer> lowLinkByNode = new HashMap<>();
        Deque<ResourceLocation> stack = new ArrayDeque<>();
        Set<ResourceLocation> onStack = new LinkedHashSet<>();
        List<Set<ResourceLocation>> components = new ArrayList<>();
        int[] nextIndex = {0};

        for (ResourceLocation node : scope) {
            if (contains(node) && !indexByNode.containsKey(node)) {
                tarjan(node, scope, indexByNode, lowLinkByNode, stack, onStack, components, nextIndex);
            }
        }
        return components;
    }

    // Tarjan 强连通分量：只在 scope 内的边上遍历，产出非平凡分量与自环单点分量
    private void tarjan(ResourceLocation node, Set<ResourceLocation> scope,
                        Map<ResourceLocation, Integer> indexByNode,
                        Map<ResourceLocation, Integer> lowLinkByNode,
                        Deque<ResourceLocation> stack, Set<ResourceLocation> onStack,
                        List<Set<ResourceLocation>> components, int[] nextIndex) {
        indexByNode.put(node, nextIndex[0]);
        lowLinkByNode.put(node, nextIndex[0]);
        nextIndex[0]++;
        stack.push(node);
        onStack.add(node);

        for (ResourceLocation child : directChildren(node)) {
            if (!scope.contains(child)) {
                continue;
            }
            if (!indexByNode.containsKey(child)) {
                tarjan(child, scope, indexByNode, lowLinkByNode, stack, onStack, components, nextIndex);
                lowLinkByNode.put(node, Math.min(lowLinkByNode.get(node), lowLinkByNode.get(child)));
            } else if (onStack.contains(child)) {
                lowLinkByNode.put(node, Math.min(lowLinkByNode.get(node), indexByNode.get(child)));
            }
        }

        if (!lowLinkByNode.get(node).equals(indexByNode.get(node))) {
            return;
        }
        LinkedHashSet<ResourceLocation> component = new LinkedHashSet<>();
        ResourceLocation popped;
        do {
            popped = stack.pop();
            onStack.remove(popped);
            component.add(popped);
        } while (!popped.equals(node));

        if (component.size() > 1) {
            components.add(component);
        } else if (directChildren(node).contains(node)) {
            components.add(component);
        }
    }

}
