package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationConstraintCatalog;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    private static final Logger LOGGER = LogUtils.getLogger();

    private final LootTableAnalysisSession session;
    private final CatalogStructure structure;
    /** 解析态视图（概率为 "?" 占位符），由本代静态投影转换而来，供模拟与无需概率的查询使用。 */
    private final Map<ResourceLocation, TableDefinition> staticTables;
    /** 每表的内容哈希，用于判断缓存是否失效。 */
    private final Map<ResourceLocation, String> tableHashes;
    /** 已填充概率的模拟结果，随模拟完成渐进增长。 */
    private final Map<ResourceLocation, TableDefinition> simulatedTables = new ConcurrentHashMap<>();
    /** 本代查询索引——子树物品等跨表聚合的唯一入口，随模拟提交读到的 overlay 自动变化。 */
    private final CatalogQueryIndex queryIndex;
    /**
     * 每表的约束描述与场景规划结果。
     * <p>
     * 在构建这一代时就为**全部**收录表算好，不是懒加载：启动只跑基准输入，而"基准输入是什么"
     * 需要先知道该表的场景规划结果（基准场景的条件赋值），因此这一步是缓存查询的前置条件，
     * 不做它就无法判断某张表是否已缓存。
     */
    private final Map<ResourceLocation, SimulationConstraintCatalog> constraintCatalogs;
    /** 本代目录哈希缓存；任一表提交新结果即失效。 */
    @SuppressWarnings("VolatileArrayField")
    private volatile String catalogHash;

    public CatalogGeneration(LootTableAnalysisSession session, CatalogStructure structure,
                             Map<ResourceLocation, TableDefinition> staticTables,
                             Map<ResourceLocation, String> tableHashes,
                             Map<ResourceLocation, SimulationConstraintCatalog> constraintCatalogs) {
        this.session = session;
        this.structure = structure;
        this.staticTables = Map.copyOf(staticTables);
        this.tableHashes = Map.copyOf(tableHashes);
        this.constraintCatalogs = Map.copyOf(constraintCatalogs);
        this.queryIndex = new CatalogQueryIndex(
                session.referenceGraph(), session.staticProjections(), this::simulatedTables);
    }

    /** 一个明确的空状态——构建失败时使用，避免发布半成品。 */
    public static CatalogGeneration empty(long generation) {
        return new CatalogGeneration(LootTableAnalysisSession.empty(generation),
                CatalogStructure.empty(), Map.of(), Map.of(), Map.of());
    }

    /** 该表的约束描述；未收录或该表不可用时返回 {@code null}。 */
    @Nullable
    public SimulationConstraintCatalog constraintCatalog(ResourceLocation tableId) {
        return this.constraintCatalogs.get(tableId);
    }

    public long generation() {
        return this.session.generation();
    }

    public LootTableAnalysisSession session() {
        return this.session;
    }

    public CatalogStructure structure() {
        return this.structure;
    }

    /** 本代查询索引（子树物品等跨表聚合的唯一入口）。 */
    public CatalogQueryIndex queryIndex() {
        return this.queryIndex;
    }

    /** 本代的解析态目录（概率为 "?" 占位符），不受模拟进度影响。 */
    public Map<ResourceLocation, TableDefinition> staticTables() {
        return this.staticTables;
    }

    /** 本代原始追踪表的数量（不含模拟期发现的动态条目所属表之外的内容）。 */
    public int trackedTableCount() {
        return this.staticTables.size();
    }

    /** 取解析态表定义；本代未收录时返回 {@code null}。 */
    @Nullable
    public TableDefinition staticTable(ResourceLocation tableId) {
        return this.staticTables.get(tableId);
    }

    /** 本代是否收录该表；不受概率模拟进度影响。 */
    public boolean isTracked(ResourceLocation tableId) {
        return this.staticTables.containsKey(tableId);
    }

    /** 该表在本代的哈希；未收录时返回空串（等价于"哈希不可用 → 触发重新模拟"）。 */
    public String tableHash(ResourceLocation tableId) {
        return this.tableHashes.getOrDefault(tableId, "");
    }

    /** 模拟结果视图（随模拟完成渐进增长）。 */
    public Map<ResourceLocation, TableDefinition> simulatedTables() {
        return Collections.unmodifiableMap(this.simulatedTables);
    }

    /** 该表是否已有模拟结果。 */
    public boolean hasSimulated(ResourceLocation tableId) {
        return this.simulatedTables.containsKey(tableId);
    }

    /** 提交单表模拟结果，并失效本代目录哈希缓存。 */
    public void publishSimulated(TableDefinition table) {
        this.simulatedTables.put(table.id(), table);
        this.catalogHash = null;
    }


    /**
     * 计算整个目录内容的 SHA-256 哈希（用于客户端按需同步比对）。
     * 输入取自网络形态 {@link CatalogTableDto}——与实际上线内容一一对应：场景假设条件树在表级
     * 只计一次，物品与子表侧只计 key 与概率，因此"改了要发的东西"必然改变哈希，反之亦然。
     * 结果在本代内缓存，任一表提交新结果即失效。
     */
    public String catalogHash() {
        String cached = this.catalogHash;
        if (cached != null) {
            return cached;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<TableDefinition> tables = this.simulatedTables.values().stream()
                    .sorted(Comparator.comparing(table -> table.id().toString()))
                    .toList();
            for (TableDefinition table : tables) {
                updateTableDigest(digest, CatalogTableDto.from(table,
                        this.tableHashes.getOrDefault(table.id(), "")));
            }
            this.structure.categories().forEach(category -> {
                updateDigest(digest, category.id().toString());
                updateDigest(digest, category.translationKey());
                updateDigest(digest, category.fallbackName());
                updateDigest(digest, category.descriptionKey());
                updateDigest(digest, category.descriptionFallback());
                updateDigest(digest, category.iconItem().toString());
                updateDigest(digest, Integer.toString(category.order()));
            });
            this.structure.rootCategories().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                    .forEach(entry -> {
                        updateDigest(digest, entry.getKey().toString());
                        updateDigest(digest, entry.getValue().toString());
                    });
            String computed = HexFormat.of().formatHex(digest.digest());
            this.catalogHash = computed;
            return computed;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warn("SHA-256 算法不可用，目录哈希将返回空字符串", e);
            return "";
        }
    }

    private static void updateTableDigest(MessageDigest digest, CatalogTableDto table) {
        updateDigest(digest, table.id().toString());
        updateDigest(digest, table.hash());
        updateDigest(digest, table.displayName().toString());
        updateDigest(digest, table.type());
        updateDigest(digest, Integer.toString(table.simulationCount()));
        table.childTables().forEach(child -> updateDigest(digest, child.toString()));
        for (CatalogTableDto.ScenarioAssumptions scenario : table.scenarios()) {
            updateDigest(digest, scenario.scenarioKey());
            updateConditionListDigest(digest, scenario.assumptions());
        }
        for (CatalogTableDto.ItemEntry item : table.items()) {
            updateDigest(digest, item.id().toString());
            updateDigest(digest, item.displayName().toString());
            updateDigest(digest, item.tooltipHint() != null ? item.tooltipHint().toString() : "");
            updateProbabilityDigest(digest, item.probability());
            updateDigest(digest, item.signature().toStoredKey());
            updateDigest(digest, Boolean.toString(item.injected()));
            for (CatalogTableDto.ScenarioRef ref : item.scenarioProbabilities()) {
                updateDigest(digest, ref.scenarioKey());
                updateProbabilityDigest(digest, ref.probability());
            }
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                updateDigest(digest, path.sourceChildTable() != null
                        ? path.sourceChildTable().toString() : "");
                updateDigest(digest, path.sourceItemTag() != null
                        ? path.sourceItemTag().toString() : "");
                updateConditionListDigest(digest, path.entryConditions());
                updateConditionListDigest(digest, path.inheritedConditions());
                updateDigest(digest, path.functionUncertainty().name());
                updateDigest(digest, Boolean.toString(path.luckAffected()));
                updateLuckGateDigest(digest, path.luckGate());
            }
        }
        for (CatalogTableDto.ChildTableEntry child : table.childProbabilities()) {
            updateDigest(digest, child.tableId().toString());
            updateProbabilityDigest(digest, child.probability());
            for (CatalogTableDto.ScenarioRef ref : child.scenarioProbabilities()) {
                updateDigest(digest, ref.scenarioKey());
                updateProbabilityDigest(digest, ref.probability());
            }
        }
    }

    // 概率按状态与数值参与摘要，避免"改了数值但显示文本未变"这类漏检
    private static void updateProbabilityDigest(MessageDigest digest, Probability probability) {
        switch (probability) {
            case Probability.Unknown unknown -> {
                updateDigest(digest, "unknown");
                // 原因也进摘要：把"尚未计算"换成"计算失败"是玩家可见的变化
                updateDigest(digest, unknown.reason().name());
            }
            case Probability.Unreachable ignored -> updateDigest(digest, "unreachable");
            case Probability.Measured measured -> {
                updateDigest(digest, "measured");
                updateDigest(digest, Double.toString(measured.lower()));
                updateDigest(digest, measured.upper().isPresent()
                        ? Double.toString(measured.upper().getAsDouble()) : "");
            }
            case Probability.NeedsCondition needsCondition -> {
                updateDigest(digest, "needs_condition");
                updateNeedsConditionDigest(digest, needsCondition.hints());
            }
        }
    }

    // 提示文本与引用目标都进摘要：它们会直接改变 tooltip 内容
    private static void updateNeedsConditionDigest(MessageDigest digest, List<PathHint> hints) {
        updateDigest(digest, Integer.toString(hints.size()));
        for (PathHint hint : hints) {
            switch (hint) {
                case PathHint.ReferencesParameter parameter -> {
                    updateDigest(digest, "parameter");
                    updateDigest(digest, parameter.kind().name());
                    updateDigest(digest, parameter.detail() != null ? parameter.detail().toString() : "");
                }
                case PathHint.ReferencesScenario scenario -> {
                    updateDigest(digest, "scenario");
                    updateConditionListDigest(digest, scenario.conditions());
                }
            }
        }
    }

    // 幸运门槛会直接改变 tooltip 文案（"需要幸运 ≥ 0.34"），因此逐字段进摘要
    private static void updateLuckGateDigest(MessageDigest digest, @Nullable LuckGate gate) {
        if (gate == null) {
            updateDigest(digest, "no_luck_gate");
            return;
        }
        updateDigest(digest, "luck_gate");
        updateDigest(digest, Boolean.toString(gate.impossible()));
        updateDigest(digest, gate.minLuck().isPresent()
                ? Double.toString(gate.minLuck().getAsDouble()) : "");
        updateDigest(digest, Boolean.toString(gate.rangeLimited()));
        updateDigest(digest, gate.bonusRollsGate().isPresent()
                ? Double.toString(gate.bonusRollsGate().getAsDouble()) : "");
    }

    private static void updateConditionListDigest(MessageDigest digest, List<LootConditionInfo> conditions) {
        updateDigest(digest, Integer.toString(conditions.size()));
        for (LootConditionInfo condition : conditions) {
            updateDigest(digest, condition.conditionType().toString());
            updateDigest(digest, condition.description().toString());
            updateDigest(digest, condition.probability() != null
                    ? Float.toString(condition.probability()) : "");
            condition.metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        updateDigest(digest, entry.getKey());
                        updateDigest(digest, entry.getValue());
                    });
            updateConditionListDigest(digest, condition.children());
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
