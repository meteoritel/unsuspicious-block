package com.meteorite.unsuspiciousblock.loottable.graph;

import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 战利品表引用关系图——引用关系的唯一权威：直接子表、可达集、子树（含自身）、
 * 强连通环集合与子树摘要。图只消费 {@link LootTableSourceSnapshot}，不产出物品、
 * 不做业务数据聚合，因此解析器、目录加载器与哈希计算都从这里取同一份拓扑。
 * <p>
 * 邻接表按目标表去重并保持出现顺序（JSON 引用在前、合成边在后）；但需要保留每个
 * 引用出现位置语义的场合（同一子表在多处的 conditions / functions 不同）由编译层
 * 独立保序，不依赖本图——图只保证"有哪些边"，不保证"引用了几次"。
 */
public final class LootTableReferenceGraph {
    private final LootTableSourceSnapshot snapshot;
    /** 表 id -> 去重且保序的出边列表。包含图内所有表（叶子表对应空列表）。 */
    private final Map<ResourceLocation, List<LootTableEdge>> adjacency;

    private LootTableReferenceGraph(LootTableSourceSnapshot snapshot,
                                    Map<ResourceLocation, List<LootTableEdge>> adjacency) {
        this.snapshot = snapshot;
        this.adjacency = adjacency;
    }

    /**
     * 建图：有效 JSON 引用 + 平台注入合成边。
     * <p>
     * 合成边按不变量 #14 施加双端存在守卫——来源与目标都必须有资源，否则该边不注入，
     * 避免图里出现快照中不存在的节点。目标已由 JSON 引用覆盖时不再重复加边。
     */
    public static LootTableReferenceGraph build(LootTableSourceSnapshot snapshot,
                                                Map<ResourceLocation, List<LootTableEdge>> syntheticEdges) {
        Map<ResourceLocation, List<LootTableEdge>> adjacency = new LinkedHashMap<>();
        for (ResourceLocation tableId : snapshot.tableIds()) {
            LinkedHashSet<ResourceLocation> seen = new LinkedHashSet<>();
            List<LootTableEdge> edges = new ArrayList<>();
            for (ResourceLocation target : snapshot.directReferences(tableId)) {
                if (seen.add(target)) {
                    edges.add(LootTableEdge.jsonReference(target));
                }
            }
            adjacency.put(tableId, List.copyOf(edges));
        }

        for (Map.Entry<ResourceLocation, List<LootTableEdge>> entry : syntheticEdges.entrySet()) {
            ResourceLocation source = entry.getKey();
            if (!snapshot.hasResource(source)) {
                continue;
            }
            List<LootTableEdge> existing = adjacency.get(source);
            LinkedHashSet<ResourceLocation> seen = new LinkedHashSet<>(targetIds(existing));
            List<LootTableEdge> merged = new ArrayList<>(existing);
            for (LootTableEdge edge : entry.getValue()) {
                if (!snapshot.hasResource(edge.target())) {
                    continue;
                }
                if (seen.add(edge.target())) {
                    merged.add(edge);
                }
            }
            adjacency.put(source, List.copyOf(merged));
        }

        return new LootTableReferenceGraph(snapshot, Collections.unmodifiableMap(adjacency));
    }

    /** 建图所依据的资源快照。 */
    public LootTableSourceSnapshot snapshot() {
        return this.snapshot;
    }

    /** 图中的全部节点（等于快照中的表，含无引用的叶子表）。 */
    public Set<ResourceLocation> nodes() {
        return this.adjacency.keySet();
    }

    /** 该表是否在图中（即有资源）。 */
    public boolean contains(ResourceLocation tableId) {
        return this.adjacency.containsKey(tableId);
    }

    /** 该表的全部出边（含目标缺失的悬空边，供诊断与告警使用）。 */
    public List<LootTableEdge> directEdges(ResourceLocation tableId) {
        return this.adjacency.getOrDefault(tableId, List.of());
    }

    /**
     * 该表的直接子表 id——只返回快照中真实存在的目标，去重且保持出现顺序。
     * <p>
     * 注意这里不判断"子表是否有物品"：无物品的表是否作为目录子表入口，由投影层按
     * 不变量 #12 过滤，图保持纯拓扑。
     */
    public List<ResourceLocation> directChildren(ResourceLocation tableId) {
        List<ResourceLocation> children = new ArrayList<>();
        for (ResourceLocation target : targetIds(directEdges(tableId))) {
            if (this.snapshot.hasResource(target)) {
                children.add(target);
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
            if (excluded.contains(current) || !this.adjacency.containsKey(current) || !result.add(current)) {
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
        if (!this.adjacency.containsKey(tableId) || !output.add(tableId)) {
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
            if (this.adjacency.containsKey(node) && !indexByNode.containsKey(node)) {
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
            if (!scope.contains(child) || !this.adjacency.containsKey(child)) {
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

    /**
     * 子树摘要——对 {@link #descendantsInclusive} 的每个节点，依次写入该表的完整资源栈摘要
     * 与编译产物摘要（由 {@code compiledProductDigest} 提供，可为 {@code null}）。
     * <p>
     * 编译产物摘要必须覆盖<b>子树内每张表</b>而不是只覆盖根表：子表引用的 item tag 成员变化时
     * JSON 文本不变，只有子表的 tag 展开结果会变，根表必须因此失效（不变量 #13）。
     */
    public void updateSubtreeDigest(ResourceLocation rootId, MessageDigest digest,
                                    @Nullable BiConsumer<ResourceLocation, MessageDigest> compiledProductDigest)
            throws IOException {
        for (ResourceLocation node : descendantsInclusive(rootId)) {
            this.snapshot.updateResourceStackDigest(node, digest);
            if (compiledProductDigest != null) {
                compiledProductDigest.accept(node, digest);
            }
        }
    }

    // 出边目标 id，去重且保持顺序（不判断目标是否存在）
    private static List<ResourceLocation> targetIds(List<LootTableEdge> edges) {
        LinkedHashSet<ResourceLocation> targets = new LinkedHashSet<>();
        for (LootTableEdge edge : edges) {
            targets.add(edge.target());
        }
        return List.copyOf(targets);
    }
}
