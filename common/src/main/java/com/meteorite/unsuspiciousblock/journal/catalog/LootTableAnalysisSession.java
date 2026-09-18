package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.loottable.catalog.StaticTableProjection;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;

/**
 * 单轮重载的分析结果——快照、引用图、编译产物与全部追踪表的静态投影，
 * 由同一个 {@code generation} 标识，构建完成后不可变。
 * <p>
 * 本记录是"这一代输入"的完整描述：投影层已经把它链接成了 {@link StaticTableProjection}，
 * 因此目录构建完成后可以整轮原子发布，不需要把半成品暴露给读取方。
 * 快照仍被持有，用于让本代的重算（重新编译、重新取引用）不必再读盘；随 generation 一起释放。
 */
public record LootTableAnalysisSession(
        long generation,
        LootTableSourceSnapshot sourceSnapshot,
        LootTableReferenceGraph referenceGraph,
        Map<ResourceLocation, CompiledLootTable> compiledTables,
        Map<ResourceLocation, StaticTableProjection> staticProjections) {

    public LootTableAnalysisSession {
        compiledTables = Map.copyOf(compiledTables);
        staticProjections = Map.copyOf(staticProjections);
    }

    /**
     * 明确的空分析结果——构建失败时的兜底状态。
     * 用空资源管理器建出真实的空快照与空图，避免任何组件为 {@code null}。
     */
    public static LootTableAnalysisSession empty(long generation) {
        LootTableSourceSnapshot emptySnapshot =
                LootTableSourceSnapshot.capture(ResourceManager.Empty.INSTANCE);
        return new LootTableAnalysisSession(generation, emptySnapshot,
                LootTableReferenceGraph.build(emptySnapshot, Map.of()), Map.of(), Map.of());
    }





}
