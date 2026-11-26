package com.meteorite.unsuspiciousblock.core.journal.catalog;

import net.minecraft.core.RegistryAccess;

import java.util.HashMap;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranchCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单一 generation 的目录状态——静态部分（分析结果 + 分类结构 + 静态表视图 + 每表哈希）
 * 在构造时一次成型且不可变，模拟结果作为 overlay 随模拟进度增长。
 * <p>
 * 发布方式：由调用方通过**单个 volatile 引用**整体发布，读取方要么看到上一代的完整状态，
 * 要么看到新一代的完整静态部分，不会读到半构建结果。模拟完成的提交必须校验 generation，
 * 旧代结果不得写入新代（见规划不变量 #11）。目录哈希缓存在本代内，任何一次 overlay 更新都
 * 只失效本代的缓存。
 */
public final class CatalogGeneration {

    private final LootTableAnalysisSession session;
    private final CatalogStructure structure;
    private final Map<ResourceLocation, List<ResourceLocation>> parents;
    private final List<String> signatureExclusions;
    private final Map<ResourceLocation, Set<ResourceLocation>> foldedItems;
    /**
     * 解析态视图（概率为 "?" 占位符），由本代静态投影转换而来，供模拟与无需概率的查询使用。
     */
    private final Map<ResourceLocation, TableDefinition> staticTables;
    /**
     * 每表的内容哈希，用于判断缓存是否失效。
     */
    private final Map<ResourceLocation, String> tableHashes;
    /**
     * 已填充概率的模拟结果，随模拟完成渐进增长。
     */
    private final Map<ResourceLocation, TableDefinition> simulatedTables = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, TableDefinition> failedBaselines = new ConcurrentHashMap<>();
    /**
     * 每表的约束描述与场景规划结果。
     * <p>
     * 在构建这一代时就为**全部**收录表算好，不是懒加载：启动只跑基准输入，而"基准输入是什么"
     * 需要先知道该表的场景规划结果（基准场景的条件赋值），因此这一步是缓存查询的前置条件，
     * 不做它就无法判断某张表是否已缓存。
     */
    private final Map<ResourceLocation, SimulationConstraintCatalog> constraintCatalogs;
    private final Map<ResourceLocation, ExpandedTable> expansions;
    private final Map<String, String> sourceNames;
    private final RegistryAccess registries;
    private final @Nullable ReloadableServerRegistries.Holder runtimeRegistries;
    private final Map<ResourceLocation, ScenarioBranchCatalog.Plan> branches = new HashMap<>();
    private long overlayRevision;
    private CatalogReadSnapshot readSnapshot;

    public CatalogGeneration(LootTableAnalysisSession session, CatalogStructure structure,
                             Map<ResourceLocation, TableDefinition> staticTables,
                             Map<ResourceLocation, String> tableHashes,
                             Map<ResourceLocation, SimulationConstraintCatalog> constraintCatalogs,
                             Map<ResourceLocation, ExpandedTable> expansions,
                             Map<String, String> sourceNames, RegistryAccess registries,
                             @Nullable ReloadableServerRegistries.Holder runtimeRegistries,
                             Map<ResourceLocation, List<ResourceLocation>> parents,
                             List<String> signatureExclusions, Map<ResourceLocation, Set<ResourceLocation>> foldedItems) {
        this.runtimeRegistries = runtimeRegistries;
        this.registries = registries;
        this.expansions = Map.copyOf(expansions);
        this.sourceNames = Map.copyOf(sourceNames);
        this.session = session;
        this.structure = structure;
        this.parents = Map.copyOf(parents);
        this.signatureExclusions = List.copyOf(signatureExclusions);
        this.foldedItems = Map.copyOf(foldedItems);
        this.staticTables = Map.copyOf(staticTables);
        this.tableHashes = Map.copyOf(tableHashes);
        this.constraintCatalogs = Map.copyOf(constraintCatalogs);
        this.staticTables.forEach((id, table) -> {
            if (this.constraintCatalogs.containsKey(id)) this.branches.put(id,
                    ScenarioBranchCatalog.plan(table, this.expansions.get(id), this.sourceNames));
        });
    }

    /**
     * 一个明确的空状态——构建失败时使用，避免发布半成品。
     */
    public static CatalogGeneration empty(long generation) {
        return new CatalogGeneration(LootTableAnalysisSession.empty(generation),
                CatalogStructure.empty(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                RegistryAccess.EMPTY, null, Map.of(), List.of(), Map.of());
    }

    boolean matchesResources(MinecraftServer server) {
        return this.runtimeRegistries == server.reloadableRegistries();
    }

    public CatalogTableDto project(TableDefinition table) {
        return CatalogTableDto.assemble(table, tableHash(table.id()), constraintCatalog(table.id()),
                this.expansions.get(table.id()), this.sourceNames, generation());
    }

    /**
     * 展开后的规划视图，复用构建时条件身份。
     */
    public @Nullable TableDefinition planningTable(ResourceLocation id) {
        ExpandedTable expansion = this.expansions.get(id);
        return expansion == null ? null : expansion.planning();
    }

    /**
     * 该表的约束描述；未收录或该表不可用时返回 {@code null}。
     */
    @Nullable
    public SimulationConstraintCatalog constraintCatalog(ResourceLocation tableId) {
        return this.constraintCatalogs.get(tableId);
    }

    /**
     * 本代展示分支与完整路径的签发索引；未收录或不可模拟时为空。
     */
    public @Nullable ScenarioBranchCatalog.Plan branches(ResourceLocation id) {
        return branches.get(id);
    }

    public long generation() {
        return this.session.generation();
    }

    public LootTableAnalysisSession session() {
        return this.session;
    }

    /** 当前目录中直接引用该表的父表。 */
    public List<ResourceLocation> parents(ResourceLocation tableId) {
        return this.parents.getOrDefault(tableId, List.of());
    }

    public CatalogStructure structure() {
        return this.structure;
    }

    /** 已发布目录的签名折叠来源。 */
    public Set<ResourceLocation> foldedItems(ResourceLocation tableId) {
        return CatalogQueryIndex.foldedItems(this.staticTables, this.foldedItems, tableId);
    }

    /** 同代静态候选构成的完成资格。 */
    public List<ItemDefinition> completionItems(ResourceLocation tableId) {
        return CatalogQueryIndex.completionItems(this.staticTables, tableId);
    }

    /**
     * 本代的解析态目录（概率为 "?" 占位符），不受模拟进度影响。
     */
    public Map<ResourceLocation, TableDefinition> staticTables() {
        return this.staticTables;
    }

    /**
     * 取解析态表定义；本代未收录时返回 {@code null}。
     */
    @Nullable
    public TableDefinition staticTable(ResourceLocation tableId) {
        return this.staticTables.get(tableId);
    }

    /**
     * 本代是否收录该表；不受概率模拟进度影响。
     */
    public boolean isTracked(ResourceLocation tableId) {
        return this.staticTables.containsKey(tableId);
    }

    /**
     * 该表在本代的哈希；未收录时返回空串（等价于"哈希不可用 → 触发重新模拟"）。
     */
    public String tableHash(ResourceLocation tableId) {
        return this.tableHashes.getOrDefault(tableId, "");
    }

    /** 冻结当代展示定义，供使用运行时发现的管理查询。 */
    public synchronized Map<ResourceLocation, TableDefinition> displayTables() {
        Map<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        this.staticTables.keySet().forEach(id -> tables.put(id, displayTable(id)));
        return Map.copyOf(tables);
    }

    /** 本代静态表的基准抽样状态。 */
    public enum BaselineStatus {
        PENDING, MEASURED, FAILED, UNAVAILABLE
    }

    /** 汇总当前发布代次中各基准状态的表数。 */
    public synchronized Map<BaselineStatus, Integer> baselineCounts() {
        Map<BaselineStatus, Integer> counts = new EnumMap<>(BaselineStatus.class);
        for (ResourceLocation id : this.staticTables.keySet()) {
            BaselineStatus status;
            if (!this.constraintCatalogs.containsKey(id)) {
                status = BaselineStatus.UNAVAILABLE;
            } else if (this.simulatedTables.containsKey(id)) {
                status = BaselineStatus.MEASURED;
            } else if (this.failedBaselines.containsKey(id)) {
                status = BaselineStatus.FAILED;
            } else {
                status = BaselineStatus.PENDING;
            }
            counts.merge(status, 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    /**
     * 同代内的展示读取，集中 overlay 优先和静态回退。
     */
    public @Nullable TableDefinition displayTable(ResourceLocation id) {
        TableDefinition simulated = this.simulatedTables.get(id);
        return simulated == null ? this.failedBaselines.getOrDefault(id, this.staticTables.get(id)) : simulated;
    }

    /**
     * 该表是否已有模拟结果。
     */
    public boolean hasSimulated(ResourceLocation tableId) {
        return this.simulatedTables.containsKey(tableId);
    }

    /**
     * 提交单表模拟结果，并失效本代目录哈希缓存。
     */
    public synchronized void publishSimulated(TableDefinition table) {
        this.simulatedTables.put(table.id(), table);
        this.failedBaselines.remove(table.id());
        this.overlayRevision++;
        this.readSnapshot = null;
    }

    /**
     * 发布基准抽样失败的展示状态，保留静态候选与获取规则。
     */
    public synchronized void publishBaselineFailure(TableDefinition table) {
        this.failedBaselines.put(table.id(), table.withUnknownProbabilities(UnknownReason.SIMULATION_FAILED));
        this.overlayRevision++;
        this.readSnapshot = null;
    }

    /**
     * 按静态目录的完整成员捕获展示快照，投影与摘要一起按 revision 缓存。
     */
    public synchronized CatalogReadSnapshot readSnapshot() {
        if (this.readSnapshot == null) {
            List<CatalogTableDto> tables = this.staticTables.keySet().stream()
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .map(this::displayTable)
                    .map(this::project).toList();
            this.readSnapshot = CatalogReadSnapshot.freeze(generation(), this.overlayRevision,
                    tables, this.structure, this.registries, this.signatureExclusions, this.foldedItems);
        }
        return this.readSnapshot;
    }

}
