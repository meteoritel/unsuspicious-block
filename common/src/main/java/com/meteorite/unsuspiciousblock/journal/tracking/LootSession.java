package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次根战利品生成的聚合会话。
 * <p>
 * 嵌套表只向会话追加发现结果，根入口在实际生成结束后提供权威最终物品并一次性提交。
 */
public final class LootSession {
    private final LootTrackingContext rootContext;
    private final LinkedHashMap<ResourceLocation, LinkedHashMap<String, Integer>> discoveredLoot =
            new LinkedHashMap<>();
    private final LinkedHashSet<ResourceLocation> discoveredTables = new LinkedHashSet<>();
    private boolean completed;

    public LootSession(LootTrackingContext rootContext) {
        this.rootContext = Objects.requireNonNull(rootContext, "rootContext");
        this.discoveredTables.add(rootContext.rootTableId());
    }

    // 记录一次当前表路径的物品；同一表在多个嵌套路径出现时合并计数
    public void capture(LootTrackingContext context, Map<String, Integer> itemCounts) {
        if (this.completed || context == null
                || !context.rootTableId().equals(this.rootContext.rootTableId())) {
            return;
        }
        Map<String, Integer> normalized = LootCounts.normalize(itemCounts);
        if (normalized.isEmpty()) {
            return;
        }
        for (ResourceLocation tableId : context.tableStack()) {
            this.discoveredTables.add(tableId);
            LinkedHashMap<String, Integer> tableLoot = this.discoveredLoot.computeIfAbsent(
                    tableId, ignored -> new LinkedHashMap<>());
            LootCounts.mergeInto(tableLoot, normalized);
        }
    }

    // 用根入口观察到的最终结果完成会话；根表计数以最终结果为准，避免与子表捕获重复
    public Commit complete(Map<String, Integer> finalItemCounts) {
        if (this.completed) {
            throw new IllegalStateException("LootSession 不能重复提交");
        }
        this.completed = true;

        Map<String, Integer> normalizedFinal = LootCounts.normalize(finalItemCounts);
        if (normalizedFinal.isEmpty()) {
            this.discoveredLoot.remove(this.rootContext.rootTableId());
        } else {
            this.discoveredLoot.put(this.rootContext.rootTableId(), new LinkedHashMap<>(normalizedFinal));
        }

        LinkedHashMap<ResourceLocation, Map<String, Integer>> immutableDiscoveries = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, LinkedHashMap<String, Integer>> entry : this.discoveredLoot.entrySet()) {
            Map<String, Integer> normalized = entry.getKey().equals(this.rootContext.rootTableId())
                    ? normalizedFinal
                    : reconcileWithFinal(entry.getValue(), normalizedFinal);
            if (!normalized.isEmpty()) {
                immutableDiscoveries.put(entry.getKey(), Map.copyOf(normalized));
            }
        }
        List<ResourceLocation> committedTables = this.discoveredTables.stream()
                .filter(immutableDiscoveries::containsKey)
                .toList();
        return new Commit(this.rootContext, committedTables,
                Map.copyOf(immutableDiscoveries), normalizedFinal);
    }

    public LootTrackingContext rootContext() {
        return this.rootContext;
    }

    // 返回本次会话实际发现的表，供最终结果按根表和嵌套表依次解析签名
    public List<ResourceLocation> discoveredTableIds() {
        return List.copyOf(this.discoveredTables);
    }

    // 子表候选以根入口最终结果为上限，过滤掉被 loot filter 移除或替换的物品
    private static Map<String, Integer> reconcileWithFinal(Map<String, Integer> captured,
                                                           Map<String, Integer> finalItemCounts) {
        Map<String, Integer> normalizedCaptured = LootCounts.normalize(captured);
        LinkedHashMap<String, Integer> reconciled = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : normalizedCaptured.entrySet()) {
            int finalCount = finalItemCounts.getOrDefault(entry.getKey(), 0);
            int retained = Math.min(entry.getValue(), finalCount);
            if (retained > 0) {
                reconciled.put(entry.getKey(), retained);
            }
        }
        return reconciled;
    }

    /**
     * 不可变的会话提交快照。
     */
    public record Commit(LootTrackingContext rootContext,
                         List<ResourceLocation> tableStack,
                         Map<ResourceLocation, Map<String, Integer>> discoveredLoot,
                         Map<String, Integer> finalItemCounts) {
        public Commit {
            Objects.requireNonNull(rootContext, "rootContext");
            tableStack = List.copyOf(tableStack);
            LinkedHashMap<ResourceLocation, Map<String, Integer>> immutableDiscoveries = new LinkedHashMap<>();
            discoveredLoot.forEach((tableId, itemCounts) ->
                    immutableDiscoveries.put(tableId, Map.copyOf(itemCounts)));
            discoveredLoot = Map.copyOf(immutableDiscoveries);
            finalItemCounts = Map.copyOf(finalItemCounts);
        }
    }
}
