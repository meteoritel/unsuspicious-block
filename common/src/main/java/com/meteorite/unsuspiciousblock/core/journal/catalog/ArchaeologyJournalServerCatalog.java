package com.meteorite.unsuspiciousblock.core.journal.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInputKey;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranchCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker.SimulationTicket;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionSemantics;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionPreviewContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootTableCompiler;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootMechanismSupport;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGateAnalysis;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogGeneration.BaselineStatus;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogDisplayDerivation;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.core.loottable.graph.RuntimeLootLinks;
import com.meteorite.unsuspiciousblock.core.loottable.graph.RuntimeLootLinks.InjectionEdge;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState.TableProgress;
import com.meteorite.unsuspiciousblock.core.loottable.signature.SignatureExcludedComponents;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Snapshot;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.PathHintAnalyzer;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationMeasurement;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationProfile;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenario;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.core.loottable.source.LootTableSourceSnapshot;
import com.meteorite.unsuspiciousblock.core.network.journal.CatalogSimulationDelivery;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.core.world.LootProbabilityData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 服务端目录——解析所有考古战利品表，按需通过主线程 tick 工作器填充概率。
 * <p>
 * 状态模型：整轮重载的静态部分（快照 / 引用图 / 编译产物 / 静态投影 / 每表哈希 / 分类结构）
 * 全部在局部对象上构建完成，再通过单个 volatile 引用（{@link #currentGeneration}）
 * <b>原子发布</b>，读取方只会看到上一代的完整状态或新一代的完整静态部分。模拟结果作为该代的
 * overlay 随进度增长；提交时校验 generation，旧代结果不会写入新代。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link #ensureLoaded(MinecraftServer)}：非阻塞，构建并发布新一代目录 + 从 SavedData 恢复已缓存的
 *       **基准输入** + 把未缓存的基准输入排进低优先级队列分 tick 模拟。构建失败时保留上一代完整状态；
 *       无上一代可用时进入明确的空状态。</li>
 *   <li>{@link #requestSimulation(ServerPlayer, ResourceLocation, String, List, ScenarioParams, SimulationTicket)}：
 *       由网络层在主线程调用，校验后把玩家的按需输入排进高优先级队列。</li>
 *   <li>{@link #invalidate()}：释放当代目录（含资源快照与投影），下次 ensureLoaded 重新构建。</li>
 * </ul>
 * <p>
 * 线程安全：读取走当前 {@link CatalogGeneration}——静态部分不可变、模拟 overlay 使用
 * ConcurrentHashMap，读路径无锁；写路径仅在主线程发生（ensureLoaded / 结果提交）。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    // 分析语义影响概率缓存身份，改动测量口径时更新此版本。
    private static final String SIMULATION_CACHE_VERSION = "loot-analysis-v30";

    /**
     * 唯一发布点：整代目录状态一次成型后整体替换。
     */
    private static volatile Applied applied;
    /**
     * 代次计数器；仅在主线程递增。
     */
    private static long generationCounter;
    private static boolean loadAttempted;
    private static boolean rebuildFailed;
    private static ResourceAnalysis resourceAnalysis;

    private ArchaeologyJournalServerCatalog() {
    }

    /** 首次启动时构建目录。后续失败由重载或管理命令触发重试。 */
    public static void ensureLoaded(MinecraftServer server) {
        if (!loadAttempted) {
            rebuild(server, ServerLootTableConfigManager.snapshot(), false);
        }
    }

    /** 资源或配置更新后构建并发布新目录。手动重建可替换旧概率缓存。 */
    public static boolean rebuild(MinecraftServer server, Snapshot settings, boolean discardMeasurements) {
        CachePolicy policy = discardMeasurements ? CachePolicy.REPLACE : CachePolicy.REUSE;
        loadAttempted = true;
        BuildResult buildResult;
        SignatureExcludedComponents signatureExclusions = SignatureExcludedComponents.resolve(
                settings.settings().signatureExclusions());
        try {
            buildResult = buildGeneration(server, settings, signatureExclusions, policy);
            buildResult.generation().readSnapshot();
        } catch (RuntimeException exception) {
            rebuildFailed = true;
            LOGGER.error("加载考古战利品表目录失败", exception);
            if (!canExecute(server)) {
                retireSimulations(server);
            }
            return false;
        }

        if (policy == CachePolicy.REPLACE) {
            LootProbabilityData.get(server.overworld()).clear();
        }
        LootResultPreviewCache.clear();
        CatalogGeneration generation = buildResult.generation();
        applied = new Applied(settings, signatureExclusions, generation);
        rebuildFailed = false;
        retireSimulations(server);
        enqueueSimulation(server, generation, buildResult.uncachedRequests());
        JournalCatalogHandler.broadcast(server, generation.generation());
        return true;
    }

    private static void retireSimulations(MinecraftServer server) {
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker != null) {
            for (CatalogSimulationSubmission submission : worker.retire()) {
                CatalogSimulationDelivery.deliver(server, submissionResult(SubmissionOutcome.STALE, submission, null));
            }
        }
    }

    /** 当前资源与已发布目录一致时，模拟器可以读取运行时表。 */
    public static boolean canExecute(MinecraftServer server) {
        CatalogGeneration generation = currentGeneration();
        return generation != null && generation.matchesResources(server);
    }

    /** 最近一次目录构建是否失败。 */
    public static boolean hasRebuildFailed() {
        return rebuildFailed;
    }

    private enum CachePolicy { REUSE, REPLACE }

    private static ResourceAnalysis resources(MinecraftServer server) {
        ReloadableServerRegistries.Holder registries = server.reloadableRegistries();
        if (resourceAnalysis == null || resourceAnalysis.registries() != registries) {
            ResourceManager manager = server.getResourceManager();
            LootTableSourceSnapshot sources = LootTableSourceSnapshot.capture(manager,
                    registries.getKeys(Registries.LOOT_TABLE));
            List<InjectionEdge> injectionEdges = Services.PLATFORM.activeLootTableInjections().stream()
                    .filter(edge -> sources.tableIds().contains(edge.source()) && sources.tableIds().contains(edge.target()))
                    .toList();
            LootTableReferenceGraph graph = LootTableReferenceGraph.build(sources, RuntimeLootLinks.syntheticEdges(injectionEdges));
            Map<ResourceLocation, List<DeclaredLootInjection>> declarations = new HashMap<>();
            resourceAnalysis = new ResourceAnalysis(registries, sources, graph, injectionEdges, readInjectionInputs(manager),
                    UUID.randomUUID().toString(), new LootTableCompiler(server.registryAccess(), sources),
                    JournalCategoryLoader.load(manager), id -> declarations.computeIfAbsent(id,
                            table -> List.copyOf(Services.PLATFORM.describeLootInjections(table))));
        }
        return resourceAnalysis;
    }

    record ResourceAnalysis(ReloadableServerRegistries.Holder registries, LootTableSourceSnapshot sources,
                                    LootTableReferenceGraph graph, List<InjectionEdge> injectionEdges,
                                    List<String> injectionInputs, String reloadSalt, LootTableCompiler compiler,
                                    JournalCategoryLoader.CategorySet categories,
                                    Function<ResourceLocation, List<DeclaredLootInjection>> declarations) {}

    private record Applied(Snapshot config, SignatureExcludedComponents signatureExclusions,
                           CatalogGeneration catalog) {}

    private static CatalogGeneration currentGeneration() {
        Applied current = applied;
        return current == null ? null : current.catalog();
    }

    /** 与当前目录同步发布的配置。 */
    public static @Nullable Snapshot appliedConfiguration() {
        Applied current = applied;
        return current == null ? null : current.config();
    }

    /** 读取供运行时抽样使用的签名策略。 */
    public static SignatureExcludedComponents signatureExclusions() {
        Applied current = applied;
        return current == null ? SignatureExcludedComponents.EMPTY : current.signatureExclusions();
    }

    /** 应用只影响日志和超时的配置，沿用目录代次和签名策略。 */
    public static void applyWithoutRebuild(Snapshot config) {
        Applied current = applied;
        applied = new Applied(config, current.signatureExclusions(), current.catalog());
        rebuildFailed = false;
    }

    /** 用正式目录算法构建候选，省略概率、广播与发布。 */
    public static ArchaeologyJournalCatalog.LoadResult preview(MinecraftServer server, Snapshot config) {
        return loadStaticCatalog(server, 0, resources(server), config,
                SignatureExcludedComponents.resolve(config.settings().signatureExclusions()));
    }

    private static ArchaeologyJournalCatalog.LoadResult loadStaticCatalog(MinecraftServer server, long generation,
            ResourceAnalysis resources, Snapshot config, SignatureExcludedComponents signatureExclusions) {
        return ArchaeologyJournalCatalog.load(generation, resources, server.registryAccess(),
                new LootFunctionPreviewContext(server.overworld()), config.rules(), signatureExclusions);
    }

    private static BuildResult buildGeneration(MinecraftServer server, Snapshot config,
                                               SignatureExcludedComponents signatureExclusions, CachePolicy policy) {
        long generation = ++generationCounter;
        ResourceAnalysis resources = resources(server);
        ArchaeologyJournalCatalog.LoadResult loadResult = loadStaticCatalog(
                server, generation, resources, config, signatureExclusions);
        loadResult.staticTables().keySet().stream().sorted().forEach(LootTableNames::resolveDisplayName);
        LootTableNames.logMissingTranslationSummary();
        if (!loadResult.cyclicTables().isEmpty()) {
            LOGGER.warn("检测到战利品表循环引用，排除闭环成员 {}", loadResult.cyclicTables());
        }
        var declarations = loadResult.declarations();
        var expansions = loadResult.expansions();
        Map<ResourceLocation, TableDefinition> staticTables = loadResult.staticTables();
        Map<String, String> sourceNames = new LinkedHashMap<>();
        sourceNames.put(Constants.MOD_ID, Services.PLATFORM.getModDisplayName(Constants.MOD_ID));
        declarations.values().forEach(rules -> rules.forEach(rule ->
                sourceNames.computeIfAbsent(rule.source(), Services.PLATFORM::getModDisplayName)));
        LOGGER.info("解析到 {} 个考古战利品表原始目录", staticTables.size());

        // 3. 先判不可用机制（决策 31）：这类表不能入队模拟，否则会在 getRandomItems 里抛异常后
        //    静默失败，玩家只看到满屏问号。判定结果同时决定它们的条目展示为「规则未解析」。
        Map<ResourceLocation, String> unavailableReasons = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            String diagnosis = LootMechanismSupport.diagnoseUnavailable(
                    entry.getValue().type(),
                    loadResult.session().sourceSnapshot().effectiveJson(entry.getKey()));
            if (diagnosis != null) {
                unavailableReasons.put(entry.getKey(), diagnosis);
            }
        }
        // 5. 为全部可模拟表算好约束描述（含场景规划）——启动只跑基准输入，而"基准输入是哪一个"
        //    需要先知道基准场景的条件赋值，因此这一步是缓存查询的前置条件而不是可省的预计算。
        ServerLevel level = server.overworld();
        Map<ResourceLocation, SimulationConstraintCatalog> constraintCatalogs = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            if (unavailableReasons.containsKey(entry.getKey())) {
                continue;
            }
            SimulationConstraintCatalog constraint = buildConstraintCatalog(
                    resources, loadResult.session(), entry.getKey(), expansions.get(entry.getKey()), level,
                    loadResult.defaultTools().get(entry.getKey()));
            // 只为"有信息量"的表留一行：存在可勾选环境条件或分析预算异常的表
            if (!constraint.conditions().isEmpty() || constraint.scenarioBudgetExhausted()) {
                LOGGER.info("战利品表 {} 的模拟约束：{}；可见旋钮 {}",
                        entry.getKey(), constraint.describe(), constraint.parameterKinds());
            }
            if (constraint.scenarioBudgetExhausted()) {
                unavailableReasons.put(entry.getKey(), "函数/条件分析不完整或超预算");
                LOGGER.warn("战利品表 {} 的函数/条件分析不完整或超预算，已停止数值模拟",
                        entry.getKey());
            } else {
                // 按需请求与只读缓存查询都通过约束目录准入；不可用表不发布可执行输入。
                constraintCatalogs.put(entry.getKey(), constraint);
            }
        }
        // 语义不完整的表保留静态规则，但不读缓存、不入队、不接受按需模拟。
        if (!unavailableReasons.isEmpty()) {
            Map<ResourceLocation, TableDefinition> marked = new LinkedHashMap<>(staticTables);
            unavailableReasons.keySet().forEach(tableId ->
                    marked.put(tableId, marked.get(tableId).withUnknownProbabilities(UnknownReason.UNPARSED)));
            staticTables = Map.copyOf(marked);
        }
        Map<ResourceLocation, String> tableHashes = computeTableHashes(
                resources, staticTables, loadResult.session().compiledTables(), server.registryAccess(), declarations,
                config.settings().signatureExclusions(), loadResult.defaultTools());
        if (!unavailableReasons.isEmpty()) LOGGER.warn("战利品表不可用原因：{}", unavailableReasons);
        CatalogGeneration catalogGeneration = new CatalogGeneration(
                loadResult.session(), loadResult.structure(), staticTables, tableHashes, constraintCatalogs,
                expansions, sourceNames, server.registryAccess(), server.reloadableRegistries(), loadResult.parents(),
                config.settings().signatureExclusions(), loadResult.foldedItems());

        // 6. 从 SavedData 恢复已缓存的**基准输入**，写入当代 overlay；不可用的表既不入队也不读缓存
        LootProbabilityData probabilityData = LootProbabilityData.get(level);
        List<LootProbabilitySimulationWorker.SimulationRequest> uncached = new ArrayList<>();
        int restored = 0;
        // 未命中按原因分账：这两类原因的处置完全不同（哈希变化＝内容变了，本该重算；
        // 缺少该输入的测量值＝内容没变但这条输入没算过），混成一个数字时无法判断缓存机制是否正常。
        int hashChanged = 0;
        int missingInput = 0;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            if (unavailableReasons.containsKey(tableId)) {
                continue;
            }
            String hash = tableHashes.getOrDefault(tableId, "");
            SimulationConstraintCatalog constraint = constraintCatalogs.get(tableId);
            SimulationInput baseline = constraint.baselineInput();
            if (policy == CachePolicy.REPLACE || probabilityData.needsResimulation(tableId, hash)) {
                hashChanged++;
            } else if (probabilityData.getMeasurement(tableId, baseline.key()) != null) {
                catalogGeneration.publishSimulated(deriveTable(
                        catalogGeneration, tableId, constraint, baseline, probabilityData));
                restored++;
                continue;
            } else {
                missingInput++;
            }
            {
                uncached.add(new LootProbabilitySimulationWorker.SimulationRequest(
                        tableId, entry.getValue(), baseline,
                        constraint.baselineScenario(expansions.get(tableId).planning()),
                        generation, hash, null, null));
            }
        }
        LOGGER.info("概率缓存命中 {} 个表的基准输入，{} 个待模拟（未命中原因：哈希变化 {} 个、缺少该输入的测量值 {} 个），"
                        + "{} 个不可用（共 {} 个表）",
                restored, uncached.size(), hashChanged, missingInput,
                unavailableReasons.size(), staticTables.size());
        return new BuildResult(catalogGeneration, List.copyOf(uncached));
    }

    /**
     * 由表内容派生约束描述——可勾选环境条件清单、工具基座与被引用附魔三份清单。
     * <p>
     * 工具与附魔都取自**整棵子树**：父表页签里出现的物品来自子表，其 {@code match_tool} 谓词与
     * 读附魔的机制也都写在子表里；只看本表 JSON 会让这些旋钮在父表上凭空消失。
     */
    private static SimulationConstraintCatalog buildConstraintCatalog(
            ResourceAnalysis resources, LootTableAnalysisSession session, ResourceLocation tableId, ExpandedTable expansion,
            ServerLevel level, ResourceLocation defaultToolId) {
        TableDefinition table = expansion.planning();
        SimulationScenarioPlanner.ConditionPlan plan = SimulationScenarioPlanner.planConditions(tableId, table);
        SimulationProfile baseProfile = SimulationProfile.eligibleConditions(level, table.type())
                .withTool(new ItemStack(BuiltInRegistries.ITEM.get(defaultToolId)));

        Map<ResourceLocation, String> tools = new LinkedHashMap<>();
        boolean toolSelectionAllowed = false;
        Map<ResourceLocation, Integer> enchantmentLevels = new LinkedHashMap<>();
        HolderLookup.RegistryLookup<Enchantment> enchantments =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (ResourceLocation node : session.referenceGraph().descendantsInclusive(tableId)) {
            toolSelectionAllowed |= session.sourceSnapshot().hasMatchTool(node);
            CompiledLootTable compiled = session.compiledTables().get(node);
            if (compiled == null) {
                continue;
            }
            compiled.referencedTools().forEach(tools::putIfAbsent);
            // 附魔清单与哈希摘要同源（JSON 引用 ∪ 本表注入边门槛）：门槛移到注入处之后，
            // 父表与子表的 JSON 都不再提这个附魔，而它正是"能不能拿到注入物"的旋钮
            for (ResourceLocation enchantmentId : summaryEnchantments(resources, compiled, node)) {
                if (enchantmentLevels.containsKey(enchantmentId)) {
                    continue;
                }
                // 附魔定义缺失（数据包只删了定义但表还引用着）时不生成等级控件：控件范围无从确定，
                // 而这已经由不可用诊断覆盖，不必在这里再猜一个上限
                enchantments.get(ResourceKey.create(Registries.ENCHANTMENT, enchantmentId))
                        .ifPresent(holder -> enchantmentLevels.put(enchantmentId,
                                holder.value().definition().maxLevel()));
            }
        }
        Set<ResourceLocation> semanticEnchantments = new LinkedHashSet<>();
        Stream.concat(table.items().stream().flatMap(item -> item.acquisitionPaths().stream())
                                .flatMap(path -> path.allConditions().stream()),
                        table.childTableProbabilities().stream().flatMap(child -> child.conditions().stream()))
                .forEach(condition -> ConditionTree.walkLogic(condition, leaf -> {
                    if (leaf.semantics() instanceof ConditionSemantics.EnchantedChance chance)
                        semanticEnchantments.add(chance.enchantment());
                    if (leaf.semantics() instanceof ConditionSemantics.ToolPredicate tool)
                        tool.enchantments().forEach(requirement -> semanticEnchantments.addAll(requirement.ids()));
                }));
        semanticEnchantments.forEach(id -> enchantments.get(ResourceKey.create(Registries.ENCHANTMENT, id))
                .ifPresent(holder -> enchantmentLevels.putIfAbsent(id, holder.value().definition().maxLevel())));
        CompiledLootTable compiled = session.compiledTables().get(tableId);
        return SimulationConstraintCatalog.build(plan, baseProfile, tools, toolSelectionAllowed,
                enchantmentLevels, expansion.childEntryGates(),
                collectChildEntryLuckGates(compiled), collectLuckAffectedChildren(compiled));
    }

    /** 汇总本表直接引用各子表的入口幸运门槛（多处引用同一子表时取最宽松入口）。 */
    private static Map<ResourceLocation, LuckGate> collectChildEntryLuckGates(
            @Nullable CompiledLootTable compiled) {
        if (compiled == null) {
            return Map.of();
        }
        Map<ResourceLocation, List<LuckGate>> gatesByTarget = new LinkedHashMap<>();
        for (CompiledLootTable.Event event : compiled.events()) {
            if (event instanceof CompiledLootTable.ReferenceSite site) {
                LuckGate siteGate = LuckGateAnalysis.analyzeChain(site.inheritedLuckSpecs(), site.luckSpec());
                gatesByTarget.computeIfAbsent(site.target(), ignored -> new ArrayList<>()).add(siteGate);
            }
        }
        Map<ResourceLocation, LuckGate> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<LuckGate>> entry : gatesByTarget.entrySet()) {
            LuckGate combined = LuckGateAnalysis.combineEntryGates(entry.getValue());
            if (!combined.isTrivial()) {
                result.put(entry.getKey(), combined);
            }
        }
        return result;
    }

    /** 收集受任意祖先池或引用权重幸运影响的直接子表 ID。 */
    private static Set<ResourceLocation> collectLuckAffectedChildren(@Nullable CompiledLootTable compiled) {
        if (compiled == null) {
            return Set.of();
        }
        Set<ResourceLocation> affected = new LinkedHashSet<>();
        for (CompiledLootTable.Event event : compiled.events()) {
            if (event instanceof CompiledLootTable.ReferenceSite site && isSiteLuckAffected(site)) {
                affected.add(site.target());
            }
        }
        return affected;
    }

    private static boolean isSiteLuckAffected(CompiledLootTable.ReferenceSite site) {
        if (site.luckAffected() || !site.luckSpec().luckNeutral()) {
            return true;
        }
        return site.inheritedLuckSpecs().stream().anyMatch(spec -> !spec.luckNeutral());
    }

    public static CatalogTableDto clientTable(TableDefinition table) {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? CatalogTableDto.assemble(table, "", null, null, Map.of(), 0) : generation.project(table);
    }

    public static CatalogReadSnapshot readSnapshot() {
        CatalogGeneration generation = currentGeneration();
        return (generation == null ? CatalogGeneration.empty(generationCounter) : generation).readSnapshot();
    }

    /**
     * 推荐/探测使用同一代的固定上下文，不暴露当代可变对象。
     */
    public record SimulationContext(long generation, String tableHash,
                                    SimulationConstraintCatalog constraints, TableDefinition planningTable,
                                    ScenarioBranchCatalog.Plan branches) {
    }

    public static @Nullable SimulationContext simulationContext(MinecraftServer server, ResourceLocation id) {
        CatalogGeneration generation = currentGeneration();
        if (generation == null || !generation.matchesResources(server) || generation.constraintCatalog(id) == null) return null;
        return new SimulationContext(generation.generation(), generation.tableHash(id),
                generation.constraintCatalog(id), generation.planningTable(id), generation.branches(id));
    }

    /**
     * 展示入口统一选择 overlay，尚未完成模拟时使用静态表。
     */
    public static @Nullable TableDefinition displayTable(ResourceLocation id) {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? null : generation.displayTable(id);
    }

    // 未缓存的**基准输入**入队分 tick 模拟；提交与排空回调都捕获本代 generation，提交时校验。
    // 新代发布时废弃旧队列，提交与排空回调仍校验代次。
    private static void enqueueSimulation(MinecraftServer server, CatalogGeneration generation,
                                          List<LootProbabilitySimulationWorker.SimulationRequest> uncached) {
        long generationId = generation.generation();
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            // 工作线程未启动（异常情况）：回退到主线程同步模拟，避免功能缺失
            LOGGER.warn("模拟工作线程未启动，回退到主线程同步模拟 {} 个基准输入", uncached.size());
            simulateSynchronously(server, uncached);
            JournalCatalogHandler.broadcast(server, generationId);
            return;
        }

        worker.enqueueBatch(uncached);
    }

    // 同步回退模拟（仅在 worker 未启动时使用）
    private static void simulateSynchronously(MinecraftServer server,
                                              List<LootProbabilitySimulationWorker.SimulationRequest> requests) {
        CatalogGeneration generation = currentGeneration();
        if (generation == null) {
            return;
        }
        for (LootProbabilitySimulationWorker.SimulationRequest request : requests) {
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(
                    request.tableId(), request.rawTable(), server.overworld(),
                    request.input(), request.scenario());
            CatalogSimulationDelivery.deliver(server, commitSimulated(result,
                    new CatalogSimulationSubmission(request, request.requester() == null
                            ? Map.of() : Map.of(request.requester(), Objects.requireNonNull(request.ticket()))), server));
        }
    }

    /**
     * 查询当前选定条件组合与参数的服务端缓存状态，并在请求时直接下发命中结果。
     */
    public static CachedStatus cachedStatus(ServerPlayer player, ResourceLocation tableId, String hash,
                                            List<String> enabledConditions, ScenarioParams params,
                                            boolean fetchSelected) {
        CatalogGeneration generation = currentGeneration();
        if (generation == null || !generation.matchesResources(player.server)
                || !generation.isTracked(tableId) || !hash.equals(generation.tableHash(tableId))) {
            throw new IllegalArgumentException("stale_hash");
        }
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        Optional<SimulationInput> resolved = constraint == null
                ? Optional.empty() : constraint.resolve(enabledConditions, params);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("rejected_input");
        }
        SimulationInput input = resolved.get();
        LootProbabilityData data = LootProbabilityData.get(player.server.overworld());
        if (data.needsResimulation(tableId, hash)) {
            return new CachedStatus(false, null);
        }
        boolean cached = data.hasMeasurement(tableId, input.key());
        ScenarioResult selected = cached && fetchSelected
                ? scenarioResult(generation, tableId, input, data) : null;
        return new CachedStatus(cached, selected);
    }

    /**
     * 服务端受理一次按需模拟请求：校验输入与表哈希，命中缓存则直接派生，否则入高优先级队列。
     */
    public static OnDemandResult requestSimulation(ServerPlayer player, ResourceLocation tableId,
                                                   String expectedTableHash, List<String> enabledConditions,
                                                   ScenarioParams params, SimulationTicket ticket) {
        CatalogGeneration generation = currentGeneration();
        if (generation == null || !generation.isTracked(tableId)) {
            return new OnDemandResult(OnDemandOutcome.UNKNOWN_TABLE, null);
        }
        if (!generation.matchesResources(player.server)) {
            return new OnDemandResult(OnDemandOutcome.STALE_HASH, null);
        }
        TableDefinition raw = generation.staticTable(tableId);
        TableDefinition planningTable = generation.planningTable(tableId);
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (raw == null || planningTable == null || constraint == null) {
            return new OnDemandResult(OnDemandOutcome.UNKNOWN_TABLE, null);
        }
        String hash = generation.tableHash(tableId);
        if (!hash.equals(expectedTableHash)) {
            return new OnDemandResult(OnDemandOutcome.STALE_HASH, null);
        }
        Optional<SimulationInput> resolved = constraint.resolve(enabledConditions, params);
        if (resolved.isEmpty()) {
            return new OnDemandResult(OnDemandOutcome.REJECTED_INPUT, null);
        }
        SimulationInput input = resolved.get();

        LootProbabilityData probabilityData = LootProbabilityData.get(player.server.overworld());
        if (!probabilityData.needsResimulation(tableId, hash)
                && probabilityData.getMeasurement(tableId, input.key()) != null) {
            LOGGER.debug("复用缓存的测量值：表 {} 输入 {}", tableId, input.key());
            return new OnDemandResult(OnDemandOutcome.CACHE_HIT, input,
                    scenarioResult(generation, tableId, input, probabilityData));
        }

        SimulationScenario scenario = constraint.buildExecutionScenario(input.enabledConditions(), planningTable);
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(
                    tableId, raw, player.server.overworld(), input, scenario);
            if (!result.successful()) {
                return new OnDemandResult(OnDemandOutcome.SIMULATION_FAILED, input);
            }
            var request = new LootProbabilitySimulationWorker.SimulationRequest(tableId, raw, input, scenario,
                    generation.generation(), hash, player.getUUID(), ticket);
            SubmissionResult committed = commitSimulated(result,
                    new CatalogSimulationSubmission(request, Map.of(player.getUUID(), ticket)), player.server);
            return new OnDemandResult(OnDemandOutcome.SIMULATED_INLINE, input, committed.scenario());
        }
        LootProbabilitySimulationWorker.EnqueueOutcome outcome = worker.enqueuePlayerRequest(
                new LootProbabilitySimulationWorker.SimulationRequest(
                        tableId, raw, input, scenario, generation.generation(), hash, player.getUUID(), ticket));
        return switch (outcome) {
            case ACCEPTED -> new OnDemandResult(OnDemandOutcome.QUEUED, input);
            case REJECTED_QUEUE_FULL -> new OnDemandResult(OnDemandOutcome.REJECTED_QUEUE_FULL, input);
            case PLAYER_LIMIT -> new OnDemandResult(OnDemandOutcome.PLAYER_LIMIT, input);
        };
    }

    /**
     * 玩家解锁一张表时的插队模拟——把它基准输入提到高优先级队列。
     */
    public static void enqueueBaselinePriority(MinecraftServer server, ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        if (generation == null || !generation.matchesResources(server) || generation.hasSimulated(tableId)) {
            return;
        }
        TableDefinition raw = generation.staticTable(tableId);
        TableDefinition planningTable = generation.planningTable(tableId);
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (raw == null || planningTable == null || constraint == null) {
            return;
        }
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            return;
        }
        worker.enqueuePriority(new LootProbabilitySimulationWorker.SimulationRequest(
                tableId, raw, constraint.baselineInput(), constraint.baselineScenario(planningTable),
                generation.generation(), generation.tableHash(tableId), null, null));
    }

    /**
     * 按需请求的受理结果——{@code input} 非空时调用方可以拿它生成回执里的输入键。
     */
    public record OnDemandResult(OnDemandOutcome outcome, @Nullable SimulationInput input,
                                 @Nullable ScenarioResult scenario) {
        public OnDemandResult(OnDemandOutcome outcome, @Nullable SimulationInput input) {
            this(outcome, input, null);
        }
    }

    /**
     * 单输入缓存查询及可选的当前选择结果，由 handler 发送。
     */
    public record CachedStatus(boolean cached, @Nullable ScenarioResult selected) {
    }

    /**
     * 完整且归属于一份签发输入的展示结果。
     */
    public record ScenarioResult(long generation, String hash, String inputKey, CatalogTableDto table) {
    }

    /**
     * 单次提交的结果，传输层只消费它，不再判定缓存或发布规则。
     */
    public record SubmissionResult(SubmissionOutcome outcome, long generation, ResourceLocation tableId,
                                   String inputKey, Map<UUID, SimulationTicket> requesters,
                                   @Nullable ScenarioResult scenario) {
    }

    /**
     * 提交终态；过期结果按原票据拒绝，重复完成不再交付。
     */
    public enum SubmissionOutcome {COMMITTED, FAILED, STALE, DUPLICATE, NO_WAITERS}

    /**
     * 按需模拟请求的受理结果。
     */
    public enum OnDemandOutcome {
        /**
         * 已入队，结果随后由 {@code SyncScenarioResultPayload} 下发。
         */
        QUEUED,
        /**
         * 该输入在当前表内容下已有测量值：当场派生并下发，未占用队列与在途额度。
         * <p>
         * 客户端表现与 {@link #QUEUED} 完全相同（都是一份结果包）；分开是为了让"这次到底复用了
         * 缓存还是又算了一遍"在受理路径上是一个可判别的事实，而不是只能靠启动日志推测。
         */
        CACHE_HIT,
        /**
         * worker 未启动，已在主线程同步算完并下发。
         */
        SIMULATED_INLINE,
        /**
         * 表未收录或不可用。
         */
        UNKNOWN_TABLE,
        /**
         * 客户端持有的表哈希已过期，需要先重新同步目录。
         */
        STALE_HASH,
        /**
         * 输入未被当前目录签发（自造参数、超界幸运、未签发的档位）。
         */
        REJECTED_INPUT,
        /**
         * 玩家请求队列已满。
         */
        REJECTED_QUEUE_FULL,
        /**
         * 该玩家的在途请求已达上限；只在**确实需要入队**时判定，缓存命中不会走到这里。
         */
        PLAYER_LIMIT,
        /**
         * 表为空或抽取过程抛异常：本次没有测量值，不写缓存。
         */
        SIMULATION_FAILED
    }

    /**
     * 当代目录代次；未加载时为 0（回执里用它让客户端丢弃过期消息）。
     */
    public static long currentGenerationId() {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? 0L : generation.generation();
    }

    /**
     * 校验提交凭据、代次、表哈希与输入归属，成功后才写缓存及共享基准。
     */
    public static SubmissionResult commitSimulated(LootProbabilitySimulator.SimResult result,
                                                   CatalogSimulationSubmission submission, MinecraftServer server) {
        var request = submission.request();
        if (!submission.claim()) return submissionResult(SubmissionOutcome.DUPLICATE, submission, null);
        CatalogGeneration generation = currentGeneration();
        if (generation == null || !generation.matchesResources(server)
                || generation.generation() != request.generation()
                || !generation.tableHash(request.tableId()).equals(request.tableHash())
                || !result.tableId().equals(request.tableId()) || !result.input().equals(request.input()))
            return submissionResult(SubmissionOutcome.STALE, submission, null);
        SimulationConstraintCatalog constraint = generation.constraintCatalog(result.tableId());
        if (constraint == null || constraint.resolve(result.input().enabledConditions(), result.input().params()).isEmpty())
            return submissionResult(SubmissionOutcome.STALE, submission, null);
        if (submission.publishesBaseline() && !request.input().equals(constraint.baselineInput()))
            return submissionResult(SubmissionOutcome.STALE, submission, null);
        if (!result.successful()) {
            if (submission.publishesBaseline()) generation.publishBaselineFailure(request.rawTable());
            return submissionResult(SubmissionOutcome.FAILED, submission, null);
        }
        LootProbabilityData probabilityData = LootProbabilityData.get(server.overworld());
        ResourceLocation tableId = result.tableId();
        String hash = request.tableHash();
        SimulationInput input = result.input();
        String inputKey = input.key();
        // 当前内容哈希下的测量才可复用；不同哈希由 putMeasurement 替换旧输入集。
        boolean alreadyStored = !probabilityData.needsResimulation(tableId, hash)
                && probabilityData.getMeasurement(tableId, inputKey) != null;
        if (!alreadyStored) {
            SimulationMeasurement measurement = result.measurement();
            Map<String, LootProbabilityData.DiscoveryRecord> discoveredNow = new LinkedHashMap<>();
            for (String signatureKey : measurement.discoveredSignatures().keySet()) {
                boolean hasDirectSource = measurement.discoveredDirectly().contains(signatureKey);
                ResourceLocation childSource = measurement.discoveredChildSources().get(signatureKey);
                discoveredNow.put(signatureKey, new LootProbabilityData.DiscoveryRecord(
                        hasDirectSource, childSource == null ? List.of() : List.of(childSource)));
            }
            // 函数观测属于**输入级测量**：它与概率一起按 (表哈希, 输入键) 落盘、一起被 LRU 淘汰。
            // 动态条目的存在性仍在表级 discovery 里，不因观测被淘汰而消失（决策 13）。
            probabilityData.putMeasurement(tableId, hash, inputKey,
                    new LootProbabilityData.InputMeasurement(input.params().sampleCount(),
                            measurement.itemProbabilities(), measurement.childProbabilities(),
                            measurement.observedFunctions()),
                    discoveredNow);
        }

        TableDefinition derived = deriveTable(generation, tableId, constraint, input, probabilityData);
        if (submission.publishesBaseline()) generation.publishSimulated(derived);
        if (submission.requesters().isEmpty()) return submissionResult(SubmissionOutcome.NO_WAITERS, submission, null);
        return submissionResult(SubmissionOutcome.COMMITTED, submission,
                new ScenarioResult(generation.generation(), hash, inputKey, generation.project(derived)));
    }

    private static SubmissionResult submissionResult(SubmissionOutcome outcome,
                                                     CatalogSimulationSubmission submission, @Nullable ScenarioResult scenario) {
        var request = submission.request();
        return new SubmissionResult(outcome, request.generation(), request.tableId(), request.input().key(),
                submission.requesters(), scenario);
    }

    private static ScenarioResult scenarioResult(CatalogGeneration generation, ResourceLocation tableId,
                                                 SimulationInput input, LootProbabilityData data) {
        TableDefinition derived = deriveTable(generation, tableId, generation.constraintCatalog(tableId), input, data);
        return new ScenarioResult(generation.generation(), generation.tableHash(tableId), input.key(), generation.project(derived));
    }



    /**
     * 释放当代目录并标记为未加载；下次 ensureLoaded 会重新构建（含资源快照与投影一并释放）
     */
    public static void invalidate() {
        applied = null;
        resourceAnalysis = null;
        loadAttempted = false;
        rebuildFailed = false;
        // 预览栈按签名内容缓存，跨代仍然有效；此处只做释放以限制内存
        LootResultPreviewCache.clear();
    }

    /** 获取本代展示定义，包含已发布的运行时发现。 */
    public static Map<ResourceLocation, TableDefinition> displayTables() {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? Map.of() : generation.displayTables();
    }

    /** 汇总本代静态目录的基准状态。 */
    public static Map<BaselineStatus, Integer> baselineCounts() {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? Map.of() : generation.baselineCounts();
    }

    /**
     * 获取原始目录的只读视图（概率为 "?" 占位符，但物品列表完整）。
     * ensureLoaded 后立即可用，不受渐进模拟影响；供成就判定等需要完整表集合的场景使用
     */
    public static Map<ResourceLocation, TableDefinition> staticTables() {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? Map.of() : generation.staticTables();
    }

    /** 返回本代实际显示为收录根表的战利品表。 */
    public static Set<ResourceLocation> rootTableIds() {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? Set.of() : generation.structure().rootCategories().keySet();
    }

    /** 返回与当前完成候选口径一致的玩家进度视图。 */
    public static TableProgress projectProgress(ResourceLocation tableId, TableProgress progress) {
        Applied current = applied;
        return current == null ? progress : progress.viewFor(current.catalog().completionItems(tableId),
                current.catalog().foldedItems(tableId), current.signatureExclusions());
    }

    /** 已发布目录中的直接父表。 */
    public static List<ResourceLocation> parents(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? List.of() : generation.parents(tableId);
    }

    /** 已发布目录中的顶层分类，子表沿打开的父表路径继承分类。 */
    public static @Nullable ResourceLocation catalogCategory(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? null : generation.structure().rootCategories().get(tableId);
    }

    /** 预览中有已有精确候选将合并到新增的物品级候选。 */
    public static boolean hasMergedVariants(@Nullable TableDefinition previous, TableDefinition next,
                                            Set<ResourceLocation> foldedItems, SignatureExcludedComponents exclusions) {
        if (previous == null) return false;
        Set<ResourceLocation> plainItems = new HashSet<>();
        Set<LootResultSignature> remaining = new HashSet<>();
        next.items().forEach(item -> {
            remaining.add(item.signature());
            if (item.signature().type() == LootResultSignature.SignatureType.PLAIN) plainItems.add(item.id());
        });
        return previous.items().stream().map(ItemDefinition::signature)
                .filter(signature -> signature.type() == LootResultSignature.SignatureType.COMPONENT_EXACT)
                .filter(signature -> foldedItems.contains(signature.itemId())
                        && plainItems.contains(signature.itemId()) && !remaining.contains(signature))
                .map(signature -> LootResultSignature.decodeComponentPatch(signature.data()))
                .anyMatch(patch -> patch != null && exclusions.containsExcluded(patch));
    }

    /** 获取指定表及后代的静态完成候选。 */
    public static List<ItemDefinition> completionItems(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? List.of() : generation.completionItems(tableId);
    }

    /**
     * 获取原始表定义（概率为占位符），供工作线程模拟时查询
     */
    @Nullable
    public static TableDefinition staticTable(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        return generation == null ? null : generation.staticTable(tableId);
    }

    // 判断指定表是否已被当前服务端的原始目录收录；不受概率模拟进度影响
    /** 只为当前运行时资源建立新的追踪归属。 */
    public static boolean tracked(MinecraftServer server, ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        return generation != null && generation.matchesResources(server) && generation.staticTable(tableId) != null;
    }

    /** 已捕获的会话和显示路径可以读取最近发布的目录。 */
    public static boolean tracked(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration();
        return generation != null && generation.isTracked(tableId);
    }

    /**
     * 由静态结构与缓存中的测量值派生**展示用**的表定义。
     * <p>
     * 这是本轮唯一的展示派生入口：从缓存恢复与刚刚算完都走它，因此不可能出现"两条路径两套口径"。
     * 派生输入有三样——该表的场景规划（含每个场景适用的签名集合）、指定输入下的测量值、
     * 以及表级发现记录（动态条目）。
     * <p>
     * 展示值的派生规则集中在 {@link PathHintAnalyzer#deriveDisplay}：**只有**测到非零值才报数字，
     * 零命中报「未命中」，被旋钮挡住报「需要条件」，全部路径静态不可达才报 {@code 0%}。
     * 分场景列表仅包含同参数下有测量缓存的场景，且采用相同展示派生；缺少引用表示尚未计算。
     */
    private static TableDefinition deriveTable(CatalogGeneration generation, ResourceLocation tableId,
                                               SimulationConstraintCatalog constraint, SimulationInput input, LootProbabilityData probabilityData) {
        TableDefinition raw = generation.staticTable(tableId);
        TableDefinition planning = generation.planningTable(tableId);
        if (raw == null || planning == null) throw new IllegalStateException("表不在当代目录中: " + tableId);
        return CatalogDisplayDerivation.derive(raw, constraint, planning, input,
                new CatalogDisplayDerivation.Readings(measurementsByScenario(tableId, input.params(),
                        probabilityData), probabilityData.getDiscovery(tableId)));
    }

    private static Map<String, LootProbabilityData.InputMeasurement> measurementsByScenario(
            ResourceLocation tableId, ScenarioParams params, LootProbabilityData probabilityData) {
        String marker = SimulationInputKey.ofScenarioSegment("", params);
        String paramsSuffix = marker.substring(marker.indexOf('|'));
        Map<String, LootProbabilityData.InputMeasurement> result = new LinkedHashMap<>();
        for (Map.Entry<String, LootProbabilityData.InputMeasurement> entry :
                probabilityData.getMeasurements(tableId).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("scenario=") || !key.endsWith(paramsSuffix)) {
                continue;
            }
            int separator = key.indexOf('|');
            if (separator > "scenario=".length()) {
                result.put(key.substring("scenario=".length(), separator), entry.getValue());
            }
        }
        return result;
    }

    // 外部依赖按代失效：reference 的间接引用与 furnace_smelt 的配方、标签、组件
    // 不做不完整的内容摘要。只给相关表及其父表加盐，同一代内仍复用输入缓存。
    // 遍历原始函数 JSON，避免展示树的裁剪、深度限制影响依赖识别。
    private static boolean hasReloadSensitiveFunctions(@Nullable CompiledLootTable compiled) {
        if (compiled == null) {
            return true;
        }
        Deque<Iterator<JsonElement>> pending = new ArrayDeque<>();
        int visited = 0;
        for (CompiledLootTable.Event event : compiled.events()) {
            List<List<JsonElement>> chains = switch (event) {
                case CompiledLootTable.ItemPath path -> List.of(path.entryFunctions(), path.inheritedFunctions());
                case CompiledLootTable.ReferenceSite site -> List.of(site.siteFunctions(), site.inheritedFunctions());
            };
            for (List<JsonElement> chain : chains) {
                pending.push(chain.iterator());
                while (!pending.isEmpty()) {
                    var iterator = pending.peek();
                    if (!iterator.hasNext()) {
                        pending.pop();
                        continue;
                    }
                    if (++visited > 4096) {
                        return true;
                    }
                    JsonElement element = iterator.next();
                    if (element.isJsonArray()) {
                        pending.push(element.getAsJsonArray().iterator());
                    } else if (element.isJsonObject()) {
                        JsonObject object = element.getAsJsonObject();
                        JsonElement type = object.get("function");
                        if (type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()) {
                            String id = type.getAsString();
                            if (id.equals("minecraft:reference") || id.equals("reference")
                                    || id.equals("minecraft:furnace_smelt") || id.equals("furnace_smelt")) {
                                return true;
                            }
                        }
                        pending.push(object.asMap().values().iterator());
                    }
                }
            }
        }
        return false;
    }

    private static List<String> readInjectionInputs(ResourceManager resources) {
        try {
            return Services.PLATFORM.lootInjectionHashInputs(resources);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("读取平台注入摘要失败，本代不复用旧缓存", exception);
            return List.of("injection-unavailable", UUID.randomUUID().toString());
        }
    }

    private static Map<ResourceLocation, String> computeTableHashes(
            ResourceAnalysis resources, Map<ResourceLocation, TableDefinition> tables,
            Map<ResourceLocation, CompiledLootTable> compiledTables, HolderLookup.Provider registries,
            Map<ResourceLocation, List<DeclaredLootInjection>> declarations, List<String> signatureExclusions,
            Map<ResourceLocation, ResourceLocation> defaultTools) {
        MessageDigest digest = LootTableSourceSnapshot.newDigest();
        LootTableSourceSnapshot.updateDigest(digest, SIMULATION_CACHE_VERSION);
        resources.injectionInputs().forEach(value -> LootTableSourceSnapshot.updateDigest(digest, value));
        signatureExclusions.stream().sorted()
                .forEach(id -> LootTableSourceSnapshot.updateDigest(digest, id));
        byte[] sharedDigest = digest.digest();

        Map<ResourceLocation, byte[]> nodeDigests = new HashMap<>();
        Map<ResourceLocation, String> hashes = new LinkedHashMap<>();
        for (ResourceLocation tableId : tables.keySet()) {
            digest.update(sharedDigest);
            LootTableSourceSnapshot.updateDigest(digest, defaultTools.get(tableId).toString());
            for (ResourceLocation node : resources.graph().descendantsInclusive(tableId)) {
                byte[] localDigest = nodeDigests.computeIfAbsent(node, id -> {
                    MessageDigest local = LootTableSourceSnapshot.newDigest();
                    LootTableSourceSnapshot.updateDigest(local, id.toString());
                    resources.sources().updateSourceDigest(id, local);
                    for (var edge : resources.graph().directEdges(id)) {
                        LootTableSourceSnapshot.updateDigest(local, edge.kind().name());
                        LootTableSourceSnapshot.updateDigest(local, edge.target().toString());
                    }
                    CompiledLootTable compiled = compiledTables.get(id);
                    updateCompiledProductDigest(local, tables.get(id));
                    if (compiled != null) {
                        compiled.referencedTools().keySet().stream().sorted()
                                .forEach(tool -> LootTableSourceSnapshot.updateDigest(local, tool.toString()));
                        // 保留标签成员与引用位置的对应关系，重叠标签也会改变实际抽取权重。
                        for (var event : compiled.events()) {
                            if (event instanceof CompiledLootTable.ItemPath path && path.sourceItemTag() != null) {
                                LootTableSourceSnapshot.updateDigest(local, path.sourceItemTag().toString());
                                LootTableSourceSnapshot.updateDigest(local, path.itemId().toString());
                            }
                        }
                    }
                    updateInjectionDigest(local, declarations.getOrDefault(id, List.of()));
                    updateEnchantmentDigest(local, registries, summaryEnchantments(resources, compiled, id));
                    if (hasReloadSensitiveFunctions(compiled)) {
                        LootTableSourceSnapshot.updateDigest(local, resources.reloadSalt());
                    }
                    return local.digest();
                });
                digest.update(localDigest);
            }
            hashes.put(tableId, HexFormat.of().formatHex(digest.digest()));
        }
        return hashes;
    }

    private static void updateInjectionDigest(MessageDigest digest, List<DeclaredLootInjection> declarations) {
        for (var rule : declarations) {
            LootTableSourceSnapshot.updateDigest(digest, rule.item() + "|" + rule.source()
                    + "|" + rule.mode() + "|" + rule.chance());
            for (var condition : rule.conditions()) {
                LootTableSourceSnapshot.updateDigest(digest, ConditionIdentity.of(condition).key());
            }
        }
    }

    // 与模拟参数共用本表的附魔依赖，包含实际启用的注入门槛。
    private static Set<ResourceLocation> summaryEnchantments(ResourceAnalysis resources,
            @Nullable CompiledLootTable compiled, ResourceLocation table) {
        Set<ResourceLocation> enchantments = new HashSet<>();
        if (compiled != null) enchantments.addAll(compiled.referencedEnchantments());
        for (InjectionEdge edge : resources.injectionEdges()) {
            if (edge.source().equals(table)) enchantments.add(edge.gate().enchantmentId());
        }
        return enchantments;
    }

    /**
     * 被引用附魔的定义摘要——只取 {@code max_level}。
     * <p>
     * 取这个字段而不是整份定义：决定模拟行为的是等级上限（它决定模拟用的满级工具与等级控件范围），
     * 其余字段（anvil 花费、权重等）不影响任何概率。因此本摘要**不覆盖**附魔定义的其它字段——
     * 这一条残余与其余外部注册表依赖一起写进 {@code docs/dev/internals/loottable-mechanics.md}。
     */
    private static void updateEnchantmentDigest(MessageDigest digest, HolderLookup.Provider registries,
                                                Set<ResourceLocation> enchantments) {
        if (enchantments.isEmpty()) {
            LootTableSourceSnapshot.updateDigest(digest, "no_enchantment");
            return;
        }
        List<ResourceLocation> sorted = enchantments.stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        for (ResourceLocation enchantmentId : sorted) {
            LootTableSourceSnapshot.updateDigest(digest, enchantmentId.toString());
            var holder = lookup.get(ResourceKey.create(Registries.ENCHANTMENT, enchantmentId));
            if (holder.isEmpty()) {
                LootTableSourceSnapshot.updateDigest(digest, "missing_definition");
                continue;
            }
            LootTableSourceSnapshot.updateDigest(digest,
                    Integer.toString(holder.get().value().definition().maxLevel()));
        }
    }

    // 编译产物摘要——tag 展开后的物品签名与物品 id，是"JSON 不变但解析结果变"的唯一失效信号。
    // 表未被解析（无物品）时写入固定标记，使"空表变为有物品"同样能改变父表摘要。
    private static void updateCompiledProductDigest(MessageDigest digest, @Nullable TableDefinition table) {
        if (table == null) {
            LootTableSourceSnapshot.updateDigest(digest, "no_compiled_product");
            return;
        }
        List<ItemDefinition> items = table.items().stream()
                .sorted(Comparator.comparing(item -> item.signature().toStoredKey()))
                .toList();
        LootTableSourceSnapshot.updateDigest(digest, Integer.toString(items.size()));
        for (ItemDefinition item : items) {
            LootTableSourceSnapshot.updateDigest(digest, item.signature().toStoredKey());
            LootTableSourceSnapshot.updateDigest(digest, item.id().toString());
        }
    }

    /**
     * 一轮构建的产物：可发布的当代目录、待入队模拟的基准输入，以及无法模拟的表。
     */
    private record BuildResult(CatalogGeneration generation,
                               List<LootProbabilitySimulationWorker.SimulationRequest> uncachedRequests) {
    }
}
