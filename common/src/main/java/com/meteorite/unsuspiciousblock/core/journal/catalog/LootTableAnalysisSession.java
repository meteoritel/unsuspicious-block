package com.meteorite.unsuspiciousblock.core.journal.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.core.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.core.loottable.source.LootTableSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;
import java.util.List;

/**
 * 单轮重载共享的不可变资源快照、引用图与编译产物。
 */
public record LootTableAnalysisSession(
        long generation,
        LootTableSourceSnapshot sourceSnapshot,
        LootTableReferenceGraph referenceGraph,
        Map<ResourceLocation, CompiledLootTable> compiledTables) {

    public LootTableAnalysisSession {
        compiledTables = Map.copyOf(compiledTables);
    }

    /**
     * 明确的空分析结果——构建失败时的兜底状态。
     * 用空资源管理器建出真实的空快照与空图，避免任何组件为 {@code null}。
     */
    public static LootTableAnalysisSession empty(long generation) {
        LootTableSourceSnapshot emptySnapshot =
                LootTableSourceSnapshot.capture(ResourceManager.Empty.INSTANCE, List.of());
        return new LootTableAnalysisSession(generation, emptySnapshot,
                LootTableReferenceGraph.build(emptySnapshot, Map.of()), Map.of());
    }
}
