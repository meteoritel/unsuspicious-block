package com.meteorite.unsuspiciousblock.core.journal.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInputKey;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionPreviewContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootTableCompiler;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootMechanismSupport;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogDisplayDerivation;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.core.loottable.graph.RuntimeLootLinks;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.core.loottable.signature.SignatureExcludedComponents;
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
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.core.world.LootProbabilityData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 *   <li>{@link #requestSimulation(ServerPlayer, ResourceLocation, String, String, ScenarioParams)}：
 *       由网络层在主线程调用，校验后把玩家的按需输入排进高优先级队列。</li>
 *   <li>{@link #invalidate()}：释放当代目录（含资源快照与投影），下次 ensureLoaded 重新构建。</li>
 * </ul>
 * <p>
 * 线程安全：读取走当前 {@link CatalogGeneration}——静态部分不可变、模拟 overlay 使用
 * ConcurrentHashMap，读路径无锁；写路径仅在主线程发生（ensureLoaded / 结果提交）。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * 统计口径版本——它的唯一用途就是"改了会影响数字的东西，就让旧测量值全部作废"。
     * <p>
     * v19：场景键改为**稳定身份**（{@code baseline} / {@code scene-N}），不再编码条件指纹。
     * 指纹（{@code entity_properties} / {@code location_check} 等）跨 JVM 运行不重复，旧存档里那批
     * 指纹键属于"同一个场景的另一个名字"，会因为 LRU 按参数组合计数而既不被覆盖也不被淘汰，
     * 永久留在存档里。升版本让它们整体作废，避免留下一批孤儿条目。
     * <p>
     * v18：输入的参数旋钮（幸运、工具、附魔等级）此前**没有真正进入抽取**——任务用合成后的 profile
     * 开条件作用域，却用场景自带的 profile 构造 {@code LootParams}，于是每次模拟实际上都跑在
     * "默认工具 + 幸运 1.0"上。修好之后，旧存档里那批数字的统计口径与现在不同，必须整体失效，
     * 否则它们会被当作缓存命中继续展示。
     * <p>
     * v22：函数规则描述改为结构化（{@code LootFunctionInfo}）后，静态规则参与目录摘要、条目 tooltip
     * 与函数条件归属都变了，且测量里新增了运行时函数观测摘要。旧测量是按旧描述规则算出来的，
     * 沿用会展示过期口径；{@code LootProbabilityData} 的 {@code format_version} 同时升到 5，
     * 两者共同保证旧数据不会被当成缓存命中。
     */
    private static final String SIMULATION_CACHE_VERSION = "loot-analysis-v27";

    /** 唯一发布点：整代目录状态一次成型后整体替换。 */
    private static volatile CatalogGeneration currentGeneration;
    /** 代次计数器；仅在主线程递增。 */
    private static long generationCounter;
    private static volatile boolean loaded;

    private ArchaeologyJournalServerCatalog() {
    }

    /**
     * 非阻塞加载：构建新一代目录 → 从 SavedData 恢复缓存 → 未缓存表入队后台模拟。
     * 调用后目录立即可用（仅含缓存表），未缓存表的概率为 "?" 占位符，后台模拟完成后渐进填充。
     */
    public static void ensureLoaded(MinecraftServer server) {
        if (loaded) return;

        List<LootProbabilitySimulationWorker.SimulationRequest> uncached = List.of();
        try {
            BuildResult buildResult = buildGeneration(server);
            currentGeneration = buildResult.generation();
            uncached = buildResult.uncachedRequests();
        } catch (Exception e) {
            LOGGER.error("加载考古战利品表目录失败", e);
            // 构建失败时进入明确的空状态而不是把半成品留在引用上；
            // invalidate 与失败路径都会让 loaded=false 与 currentGeneration=null 成对出现，
            // 因此这里不存在"上一代可保留"的情形。
            currentGeneration = CatalogGeneration.empty(++generationCounter);
        }
        loaded = true;

        CatalogGeneration generation = currentGeneration;
        if (generation != null && !uncached.isEmpty()) {
            enqueueSimulation(server, generation, uncached);
        }
    }

    /**
     * 在局部对象上构建整代目录状态；全部成功后才由调用方发布。
     * 失败时抛出，由 {@link #ensureLoaded} 发布明确的空状态。
     */
    private static BuildResult buildGeneration(MinecraftServer server) {
        long generation = ++generationCounter;

        // 1. 捕获本轮资源快照（全表有效原文 + 完整资源栈），编译与哈希共用同一次读盘
        LootTableSourceSnapshot sourceSnapshot = LootTableSourceSnapshot.capture(server.getResourceManager());

        // 2. 建图 → 编译 → 投影 → 组装静态读模型
        ArchaeologyJournalCatalog.LoadResult loadResult = ArchaeologyJournalCatalog.load(
                generation, sourceSnapshot, server.getResourceManager(), server.registryAccess(),
                new LootFunctionPreviewContext(server.overworld()));
        Map<ResourceLocation, List<DeclaredLootInjection>> declarations = new LinkedHashMap<>();
        Map<String, String> sourceNames = new LinkedHashMap<>();
        sourceNames.put(Constants.MOD_ID,
                Services.PLATFORM.getModDisplayName(Constants.MOD_ID));
        Set<ResourceLocation> declarationTables = new LinkedHashSet<>();
        loadResult.staticTables().keySet().forEach(id -> declarationTables.addAll(
                loadResult.session().referenceGraph().descendantsInclusive(id)));
        for (ResourceLocation id : declarationTables) {
            List<DeclaredLootInjection> rules = List.copyOf(Services.PLATFORM.describeLootInjections(id));
            declarations.put(id, rules);
            for (DeclaredLootInjection rule : rules) sourceNames.computeIfAbsent(rule.source(),
                    Services.PLATFORM::getModDisplayName);
        }
        Map<ResourceLocation, TableDefinition> projectionTables = new LinkedHashMap<>(loadResult.staticTables());
        loadResult.session().staticProjections().forEach((id, projection) -> projectionTables.putIfAbsent(id,
                new TableDefinition(id, Component.literal(id.toString()), projection.declaredType(),
                        projection.items(), 0, projection.childTables())));
        Map<ResourceLocation, ExpandedTable> expansions = DeclaredInjectionExpansion.build(projectionTables,
                declarations, RuntimeLootLinks.injectionEdges(), server.registryAccess());
        Map<ResourceLocation, TableDefinition> staticTables = new LinkedHashMap<>();
        for (ResourceLocation id : loadResult.staticTables().keySet()) staticTables.put(id, expansions.get(id).display());
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
                    loadResult.session(), entry.getKey(), expansions.get(entry.getKey()), level,
                    loadResult.defaultTools().get(entry.getKey()));
            // 只为"有信息量"的表留一行：单场景且无截断的表没什么可说的，逐表刷屏会淹掉真正的异常
            if (constraint.scenarios().size() > 1 || constraint.truncatedScenarioCount() > 0
                    || constraint.scenarioBudgetExhausted()) {
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
                    marked.put(tableId, markUnparsed(marked.get(tableId))));
            staticTables = Map.copyOf(marked);
        }
        Map<ResourceLocation, String> tableHashes = computeTableHashes(
                loadResult.session().referenceGraph(), staticTables,
                loadResult.session().compiledTables(), server.registryAccess(), server.getResourceManager(), declarations);
        if (!unavailableReasons.isEmpty()) LOGGER.warn("战利品表不可用原因：{}", unavailableReasons);
        CatalogGeneration catalogGeneration = new CatalogGeneration(
                loadResult.session(), loadResult.structure(), staticTables, tableHashes, constraintCatalogs,
                expansions, sourceNames, server.registryAccess());

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
                // 不可用表也要发布：否则它在客户端**整表消失**，玩家连"这张表读不了"都看不到。
                catalogGeneration.publishSimulated(entry.getValue());
                continue;
            }
            String hash = tableHashes.getOrDefault(tableId, "");
            SimulationConstraintCatalog constraint = constraintCatalogs.get(tableId);
            SimulationInput baseline = constraint.baselineInput();
            if (probabilityData.needsResimulation(tableId, hash)) {
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
                        tableId, entry.getValue(), baseline, constraint.baselineScenario(),
                        generation, hash, null));
            }
        }
        // 四个数字必须各算各的：不可用表也走 publishSimulated，直接读 overlay 大小会把它们算成"从缓存恢复"；
        // 两类未命中也要分开报，否则"缓存机制坏了"与"内容确实变了"看起来一模一样。
        LOGGER.info("概率缓存命中 {} 个表的基准输入，{} 个待模拟（未命中原因：哈希变化 {} 个、缺少该输入的测量值 {} 个），"
                        + "{} 个不可用（共 {} 个表）",
                restored, uncached.size(), hashChanged, missingInput,
                unavailableReasons.size(), staticTables.size());
        return new BuildResult(catalogGeneration, List.copyOf(uncached),
                Set.copyOf(unavailableReasons.keySet()));
    }

    /**
     * 由表内容派生约束描述——场景规划结果、工具基座与被引用附魔三份清单。
     * <p>
     * 工具与附魔都取自**整棵子树**：父表页签里出现的物品来自子表，其 {@code match_tool} 谓词与
     * 读附魔的机制也都写在子表里；只看本表 JSON 会让这些旋钮在父表上凭空消失。
     */
    private static SimulationConstraintCatalog buildConstraintCatalog(
            LootTableAnalysisSession session, ResourceLocation tableId, ExpandedTable expansion,
            ServerLevel level, ResourceLocation defaultToolId) {
        TableDefinition table = expansion.planning();
        SimulationScenarioPlanner.ScenarioPlan plan = SimulationScenarioPlanner.plan(tableId, table, level);
        SimulationProfile baseProfile = SimulationProfile.eligibleConditions(level, table.type())
                .withTool(new ItemStack(BuiltInRegistries.ITEM.get(defaultToolId)));

        Map<ResourceLocation, String> tools = new LinkedHashMap<>();
        boolean toolSelectionAllowed = false;
        Map<ResourceLocation, Integer> enchantmentLevels = new LinkedHashMap<>();
        HolderLookup.RegistryLookup<Enchantment> enchantments =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (ResourceLocation node : session.referenceGraph().descendantsInclusive(tableId)) {
            toolSelectionAllowed |= LootTableCompiler.hasMatchTool(session.sourceSnapshot().effectiveJson(node));
            CompiledLootTable compiled = session.compiledTables().get(node);
            if (compiled == null) {
                continue;
            }
            compiled.referencedTools().forEach(tools::putIfAbsent);
            // 附魔清单与哈希摘要同源（JSON 引用 ∪ 本表注入边门槛）：门槛移到注入处之后，
            // 父表与子表的 JSON 都不再提这个附魔，而它正是"能不能拿到注入物"的旋钮
            for (ResourceLocation enchantmentId : summaryEnchantments(compiled, node)) {
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
        return SimulationConstraintCatalog.build(plan, baseProfile, tools, toolSelectionAllowed, enchantmentLevels,
                expansion.childEntryGates());
    }

    public static CatalogTableDto clientTable(TableDefinition table) {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? CatalogTableDto.assemble(table, "", null, null, Map.of(), 0) : generation.project(table);
    }

    public static CatalogReadSnapshot readSnapshot() {
        CatalogGeneration generation = currentGeneration;
        return (generation == null ? CatalogGeneration.empty(generationCounter) : generation).readSnapshot();
    }

    /** 推荐/探测使用同一代的固定上下文，不暴露当代可变对象。 */
    public record SimulationContext(long generation, String tableHash,
            SimulationConstraintCatalog constraints, TableDefinition planningTable) {}

    public static @Nullable SimulationContext simulationContext(ResourceLocation id) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.constraintCatalog(id) == null) return null;
        return new SimulationContext(generation.generation(), generation.tableHash(id),
                generation.constraintCatalog(id), generation.planningTable(id));
    }

    /** 展示入口统一选择 overlay，尚未完成模拟时使用静态表。 */
    public static @Nullable TableDefinition displayTable(ResourceLocation id) {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? null : generation.displayTable(id);
    }

    // 不可用表的条目一律标记为「规则未解析」：这是与"未覆盖""尚未计算"都不同的失败原因，
    // 玩家据此知道该表的规则本模组读不了，而不是自己的处境问题
    private static TableDefinition markUnparsed(TableDefinition table) {
        List<ItemDefinition> items = table.items().stream()
                .map(item -> new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                        Probability.unknown(UnknownReason.UNPARSED), item.signature(),
                        item.acquisitionPaths(), item.injected(), List.of(), item.origins(), null))
                .toList();
        List<ChildTableProbability> children = table.childTableProbabilities().stream()
                .map(child -> new ChildTableProbability(child.tableId(),
                        Probability.unknown(UnknownReason.UNPARSED), List.of(),
                        // 规则未解析时仍然如实给出"入口需要什么"：那是静态声明，不依赖解析结果
                        child.conditions()))
                .toList();
        return new TableDefinition(table.id(), table.displayName(), table.type(), items,
                table.simulationCount(), table.childTables(), children);
    }

    // 未缓存的**基准输入**入队分 tick 模拟；提交与排空回调都捕获本代 generation，提交时校验。
    // 这里依赖既有 reload 协议：数据包重载走 pause → clearQueue → invalidate → ensureLoaded → resume，
    // 队列与在跑任务都会被丢弃，且暂停期间不会产出结果，因此旧代结果不可能落到新代处理器上。
    private static void enqueueSimulation(MinecraftServer server, CatalogGeneration generation,
                                         List<LootProbabilitySimulationWorker.SimulationRequest> uncached) {
        long generationId = generation.generation();
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            // 工作线程未启动（异常情况）：回退到主线程同步模拟，避免功能缺失
            LOGGER.warn("模拟工作线程未启动，回退到主线程同步模拟 {} 个基准输入", uncached.size());
            simulateSynchronously(server, uncached);
            CatalogSimulationDelivery.broadcastHash(server, generationId);
            return;
        }

        CatalogSimulationDelivery.install(worker, generationId);
        worker.enqueueBatch(uncached);
    }

    // 同步回退模拟（仅在 worker 未启动时使用）
    private static void simulateSynchronously(MinecraftServer server,
                                             List<LootProbabilitySimulationWorker.SimulationRequest> requests) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null) {
            return;
        }
        for (LootProbabilitySimulationWorker.SimulationRequest request : requests) {
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(
                    request.tableId(), request.rawTable(), server.overworld(),
                    request.input(), request.scenario());
            CatalogSimulationDelivery.deliver(server, commitSimulated(result,
                    new CatalogSimulationSubmission(request, request.requester() == null
                            ? Set.of() : Set.of(request.requester())), server));
        }
    }

    /**
     * 服务端处理一份按需模拟请求——校验、查缓存、入队。回执与限流由调用方（网络层）负责。
     * <p>
     * 四条校验（决策 15/36）：表仍在追踪、哈希未变（变了说明这份输入属于上一版内容）、
     * 输入由当前目录签发（场景、工具、附魔等级、抽样次数逐项落在约束内）、
     * 需要入队时该玩家的在途额度还有空位。
     * <p>
     * **缓存先于队列**：同一个 {@code (表, 输入)} 可能已经由别的玩家、上一次会话或启动批次算过，
     * 而按需模型的意义正是"一个问题只算一次"。命中缓存时直接派生下发，不占队列、不烧 tick 预算，
     * 也不受在途额度限制——额度保护的是抽取成本，而这里没有抽取。
     */
    public static CachedScenes cachedScenes(ServerPlayer player, ResourceLocation tableId, String hash,
                                           String scene, ScenarioParams params, boolean fetchSelected) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || !generation.isTracked(tableId) || !hash.equals(generation.tableHash(tableId)))
            throw new IllegalArgumentException("stale_hash");
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (constraint == null || constraint.resolve(scene, params).isEmpty())
            throw new IllegalArgumentException("rejected_input");
        LootProbabilityData data = LootProbabilityData.get(player.server.overworld());
        if (data.needsResimulation(tableId, hash)) return new CachedScenes(List.of(), null);
        List<String> available = new ArrayList<>();
        ScenarioResult selected = null;
        for (SimulationScenario candidate : constraint.scenarios()) {
            SimulationInput input = constraint.resolve(candidate.key(), params).orElseThrow();
            if (!data.hasMeasurement(tableId, input.key())) continue;
            available.add(candidate.key());
            if (fetchSelected && candidate.key().equals(scene)) selected = scenarioResult(generation, tableId, input, data);
        }
        return new CachedScenes(List.copyOf(available), selected);
    }

    public static OnDemandResult requestSimulation(ServerPlayer player, ResourceLocation tableId,
                                                   String expectedTableHash, String scenarioKey,
                                                   ScenarioParams params) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || !generation.isTracked(tableId)) {
            return new OnDemandResult(OnDemandOutcome.UNKNOWN_TABLE, null);
        }
        TableDefinition raw = generation.staticTable(tableId);
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (raw == null || constraint == null) {
            return new OnDemandResult(OnDemandOutcome.UNKNOWN_TABLE, null);
        }
        String hash = generation.tableHash(tableId);
        if (!hash.equals(expectedTableHash)) {
            // 客户端拿的是上一版目录：拒绝而不是按旧参数算，否则结果会落在一个已失效的输入上
            return new OnDemandResult(OnDemandOutcome.STALE_HASH, null);
        }
        Optional<SimulationInput> resolved = constraint.resolve(scenarioKey, params);
        if (resolved.isEmpty()) {
            return new OnDemandResult(OnDemandOutcome.REJECTED_INPUT, null);
        }
        SimulationInput input = resolved.get();
        SimulationScenario scenario = constraint.scenario(scenarioKey);
        if (scenario == null) {
            return new OnDemandResult(OnDemandOutcome.REJECTED_INPUT, input);
        }

        // 读缓存前必须先过 needsResimulation：getMeasurement 只看输入键，不看内容哈希，
        // 表内容变过而条目尚未被重写时，那条测量值属于上一版内容，不能拿来当答案。
        LootProbabilityData probabilityData = LootProbabilityData.get(player.server.overworld());
        if (!probabilityData.needsResimulation(tableId, hash)
                && probabilityData.getMeasurement(tableId, input.key()) != null) {
            // debug 而不是 info：切场景是高频动作，命中一次就写一行 info 会把真正的重算日志淹掉
            LOGGER.debug("复用缓存的测量值：表 {} 输入 {}", tableId, input.key());
            return new OnDemandResult(OnDemandOutcome.CACHE_HIT, input,
                    scenarioResult(generation, tableId, input, probabilityData));
        }

        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(
                    tableId, raw, player.server.overworld(), input, scenario);
            if (!result.successful()) {
                // 失败既不写缓存也不下发：由网络层按 SIMULATION_FAILED 回执，
                // 否则玩家只能等到 120 秒超时，而超时会把"确定的失败"说成"可能只是慢"
                return new OnDemandResult(OnDemandOutcome.SIMULATION_FAILED, input);
            }
            var request = new LootProbabilitySimulationWorker.SimulationRequest(tableId, raw, input, scenario,
                    generation.generation(), hash, player.getUUID());
            SubmissionResult committed = commitSimulated(result,
                    new CatalogSimulationSubmission(request, Set.of(player.getUUID())), player.server);
            return new OnDemandResult(OnDemandOutcome.SIMULATED_INLINE, input, committed.scenario());
        }
        // 在途额度到这一步才判：缓存命中不该因为它被拒（见方法注释）
        if (!worker.canAcceptFor(player.getUUID())) {
            return new OnDemandResult(OnDemandOutcome.PLAYER_LIMIT, input);
        }
        CatalogSimulationDelivery.install(worker, generation.generation());
        LootProbabilitySimulationWorker.EnqueueOutcome outcome = worker.enqueuePlayerRequest(
                new LootProbabilitySimulationWorker.SimulationRequest(
                        tableId, raw, input, scenario, generation.generation(), hash, player.getUUID()));
        return switch (outcome) {
            case ACCEPTED -> new OnDemandResult(OnDemandOutcome.QUEUED, input);
            case REJECTED_QUEUE_FULL -> new OnDemandResult(OnDemandOutcome.REJECTED_QUEUE_FULL, input);
        };
    }

    /**
     * 玩家解锁一张表时的插队模拟——把它**基准输入**提到高优先级队列。
     * <p>
     * 只排基准输入：按需模型下其余场景由玩家在界面上选，而解锁时最需要的是"这张表的基本数字"。
     * 表已有基准结果时什么也不做（{@code hasSimulated}），避免解锁动作反复触发重算。
     */
    public static void enqueueBaselinePriority(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.hasSimulated(tableId)) {
            return;
        }
        TableDefinition raw = generation.staticTable(tableId);
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (raw == null || constraint == null) {
            return;
        }
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            return;
        }
        CatalogSimulationDelivery.install(worker, generation.generation());
        worker.enqueuePriority(new LootProbabilitySimulationWorker.SimulationRequest(
                tableId, raw, constraint.baselineInput(), constraint.baselineScenario(),
                generation.generation(), generation.tableHash(tableId), null));
    }

    /** 按需请求的受理结果——{@code input} 非空时调用方可以拿它生成回执里的输入键。 */
    public record OnDemandResult(OnDemandOutcome outcome, @Nullable SimulationInput input,
            @Nullable ScenarioResult scenario) {
        public OnDemandResult(OnDemandOutcome outcome, @Nullable SimulationInput input) {
            this(outcome, input, null);
        }
    }

    /** 缓存查询及可选的当前选择结果，由 handler 发送。 */
    public record CachedScenes(List<String> scenes, @Nullable ScenarioResult selected) {}

    /** 完整且归属于一份签发输入的展示结果。 */
    public record ScenarioResult(long generation, String hash, String inputKey, CatalogTableDto table) {}

    /** 单次提交的结果，传输层只消费它，不再判定缓存或发布规则。 */
    public record SubmissionResult(SubmissionOutcome outcome, long generation, ResourceLocation tableId,
            String inputKey, Set<UUID> requesters, @Nullable ScenarioResult scenario) {}

    /** 提交终态；过期与重复完成没有发送动作。 */
    public enum SubmissionOutcome { COMMITTED, FAILED, STALE, DUPLICATE, NO_WAITERS }

    /** 按需模拟请求的受理结果。 */
    public enum OnDemandOutcome {
        /** 已入队，结果随后由 {@code SyncScenarioResultPayload} 下发。 */
        QUEUED,
        /**
         * 该输入在当前表内容下已有测量值：当场派生并下发，未占用队列与在途额度。
         * <p>
         * 客户端表现与 {@link #QUEUED} 完全相同（都是一份结果包）；分开是为了让"这次到底复用了
         * 缓存还是又算了一遍"在受理路径上是一个可判别的事实，而不是只能靠启动日志推测。
         */
        CACHE_HIT,
        /** worker 未启动，已在主线程同步算完并下发。 */
        SIMULATED_INLINE,
        /** 表未收录或不可用。 */
        UNKNOWN_TABLE,
        /** 客户端持有的表哈希已过期，需要先重新同步目录。 */
        STALE_HASH,
        /** 输入未被当前目录签发（自造参数、超界幸运、未签发的档位）。 */
        REJECTED_INPUT,
        /** 玩家请求队列已满。 */
        REJECTED_QUEUE_FULL,
        /** 该玩家的在途请求已达上限；只在**确实需要入队**时判定，缓存命中不会走到这里。 */
        PLAYER_LIMIT,
        /** 表为空或抽取过程抛异常：本次没有测量值，不写缓存。 */
        SIMULATION_FAILED
    }

    /** 当代目录代次；未加载时为 0（回执里用它让客户端丢弃过期消息）。 */
    public static long currentGenerationId() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? 0L : generation.generation();
    }

    /** 校验提交凭据、代次、表哈希与输入归属，成功后才写缓存及共享基准。 */
    public static SubmissionResult commitSimulated(LootProbabilitySimulator.SimResult result,
            CatalogSimulationSubmission submission, MinecraftServer server) {
        var request = submission.request();
        if (!submission.claim()) return submissionResult(SubmissionOutcome.DUPLICATE, submission, null);
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.generation() != request.generation()
                || !generation.tableHash(request.tableId()).equals(request.tableHash())
                || !result.tableId().equals(request.tableId()) || !result.input().equals(request.input()))
            return submissionResult(SubmissionOutcome.STALE, submission, null);
        SimulationConstraintCatalog constraint = generation.constraintCatalog(result.tableId());
        if (constraint == null || constraint.resolve(result.input().scenarioKey(), result.input().params()).isEmpty())
            return submissionResult(SubmissionOutcome.STALE, submission, null);
        if (submission.publishesBaseline() && !request.input().equals(constraint.baselineInput()))
            return submissionResult(SubmissionOutcome.STALE, submission, null);
        if (!result.successful()) return submissionResult(SubmissionOutcome.FAILED, submission, null);
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

    /** 当前代次排空后可广播的目录摘要，旧代事件返回空串。 */
    public static String catalogHashFor(long generationId) {
        CatalogGeneration generation = currentGeneration;
        return generation == null || generation.generation() != generationId ? "" : generation.catalogHash();
    }

    // 计算整个目录内容的 SHA-256 哈希（用于按需同步比对）；缓存与失效都在当代内完成
    public static String computeCatalogHash() {
        if (!loaded) return "";
        CatalogGeneration generation = currentGeneration;
        return generation == null ? "" : generation.catalogHash();
    }

    /** 是否已完成首轮加载；平台重载监听器用它区分"启动时的首次资源加载"与真正的重载。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 释放当代目录并标记为未加载；下次 ensureLoaded 会重新构建（含资源快照与投影一并释放） */
    public static void invalidate() {
        currentGeneration = null;
        loaded = false;
        // 预览栈按签名内容缓存，跨代仍然有效；此处只做释放以限制内存
        LootResultPreviewCache.clear();
    }

    /** 获取已填充概率的目录只读视图（随模拟完成渐进增长） */
    public static Map<ResourceLocation, TableDefinition> simulatedTables() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? Map.of() : generation.simulatedTables();
    }

    /** 获取原始目录的只读视图（概率为 "?" 占位符，但物品列表完整）。
     *  ensureLoaded 后立即可用，不受渐进模拟影响；供成就判定等需要完整表集合的场景使用 */
    public static Map<ResourceLocation, TableDefinition> staticTables() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? Map.of() : generation.staticTables();
    }

    /** 获取当代查询索引（子树物品等跨表聚合的唯一入口）；目录尚未加载时返回空索引 */
    public static CatalogQueryIndex queryIndex() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? CatalogQueryIndex.EMPTY : generation.queryIndex();
    }

    /** 获取原始表定义（概率为占位符），供工作线程模拟时查询 */
    @Nullable
    public static TableDefinition staticTable(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? null : generation.staticTable(tableId);
    }

    // 判断指定表是否已被当前服务端的原始目录收录；不受概率模拟进度影响
    public static boolean tracked(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
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
        if (raw == null) throw new IllegalStateException("表不在当代目录中: " + tableId);
        return CatalogDisplayDerivation.derive(raw, constraint, input,
                new CatalogDisplayDerivation.Readings(measurementsByScenario(tableId, constraint, input.params(),
                        probabilityData), probabilityData.getDiscovery(tableId)));
    }

    /**
     * 同一份参数下、每个场景各自的测量值。
     * <p>
     * 缓存键是"场景的稳定身份 + 参数"，因此这里直接用 {@code scenario.key()} 构造输入——
     * 绝不从条件指纹反算场景键：指纹跨 JVM 运行可能不重复，反算会让同一个场景每次启动换一个键
     * （读路径按新键找不到旧的、写路径又不断新增键，存档里于是无限累积）。
     * 只有被算过的场景才有条目——这正是按需模型下"尚未计算"能如实呈现的原因。
     */
    private static Map<String, LootProbabilityData.InputMeasurement> measurementsByScenario(
            ResourceLocation tableId, SimulationConstraintCatalog constraint, ScenarioParams params,
            LootProbabilityData probabilityData) {
        Map<String, LootProbabilityData.InputMeasurement> result = new LinkedHashMap<>();
        for (SimulationScenario scenario : constraint.scenarios()) {
            String inputKey = SimulationInputKey.of(scenario.key(), params);
            LootProbabilityData.InputMeasurement measurement =
                    probabilityData.getMeasurement(tableId, inputKey);
            if (measurement != null) {
                result.put(scenario.key(), measurement);
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

    // 对每个表计算 SHA-256 哈希：缓存版本 + 该表子树的资源栈摘要、编译产物摘要与被引用附魔定义摘要。
    // 摘要按 descendantsInclusive 覆盖后代每一张表，因此子表引用的 item tag 成员变化
    // （子表 JSON 文本不变、只有 tag 展开结果变）同样会让父表失效。
    //
    // 保证范围拆成两句（决策 35），不要读成一句更大的保证：
    //   1) "表 JSON 变化必然失效"——由资源栈摘要承担，现在成立；
    //   2) 附魔定义走内容摘要；reference / furnace_smelt 的外部依赖走保守重载盐。
    // 已知残余（不列入本摘要的外部注册表依赖）见 docs/dev/internals/loottable-mechanics.md。
    private static Map<ResourceLocation, String> computeTableHashes(
            LootTableReferenceGraph graph, Map<ResourceLocation, TableDefinition> tables,
            Map<ResourceLocation, CompiledLootTable> compiledTables, HolderLookup.Provider registries,
            ResourceManager resources, Map<ResourceLocation, List<DeclaredLootInjection>> declarations) {
        Map<ResourceLocation, String> hashes = new LinkedHashMap<>();
        List<String> injectionInputs;
        try { injectionInputs = Services.PLATFORM.lootInjectionHashInputs(resources); }
        catch (IOException | RuntimeException exception) {
            LOGGER.warn("读取平台注入摘要失败，本代不复用旧缓存", exception);
            String unavailableHash = "injection-unavailable-" + UUID.randomUUID();
            tables.keySet().forEach(id -> hashes.put(id, unavailableHash));
            return hashes;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warn("SHA-256 算法不可用，所有表将触发重新模拟", e);
            for (ResourceLocation tableId : tables.keySet()) {
                hashes.put(tableId, "");
            }
            return hashes;
        }

        String reloadSalt = UUID.randomUUID().toString();
        Map<ResourceLocation, Boolean> reloadSensitive = new LinkedHashMap<>();

        for (ResourceLocation tableId : tables.keySet()) {
            try {
                digest.reset();
                LootTableSourceSnapshot.updateDigest(digest, SIMULATION_CACHE_VERSION);
                injectionInputs.forEach(value -> LootTableSourceSnapshot.updateDigest(digest, value));
                // 排除列表决定物品签名怎么派生，改配置必须让相关表失效（否则会沿用旧签名的缓存结果）
                LootTableSourceSnapshot.updateDigest(digest,
                        String.join(",", SignatureExcludedComponents.configuredIds()));
                graph.updateSubtreeDigest(tableId, digest,
                        (node, nodeDigest) -> {
                            updateCompiledProductDigest(nodeDigest, tables.get(node));
                            for (var rule : declarations.getOrDefault(node, List.of())) {
                                LootTableSourceSnapshot.updateDigest(nodeDigest, rule.item() + "|" + rule.source()
                                        + "|" + rule.mode() + "|" + rule.chance());
                                for (var condition : rule.conditions())
                                    LootTableSourceSnapshot.updateDigest(nodeDigest, ConditionIdentity.of(condition).key());
                            }
                            // 附魔定义不在任何战利品表 JSON 里，却决定模拟用的满级工具与等级控件范围
                            updateEnchantmentDigest(nodeDigest, registries,
                                    summaryEnchantments(compiledTables.get(node), node));
                            if (reloadSensitive.computeIfAbsent(node,
                                    id -> hasReloadSensitiveFunctions(compiledTables.get(id)))) {
                                LootTableSourceSnapshot.updateDigest(nodeDigest, reloadSalt);
                            }
                        });
                hashes.put(tableId, HexFormat.of().formatHex(digest.digest()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("计算战利品表 {} 哈希失败，将触发重新模拟", tableId, e);
                hashes.put(tableId, "");
            }
        }
        return hashes;
    }

    // 编译产物记录的"本表引用到的附魔"；表未被编译时写入固定标记，
    // 使"从无引用变为有引用"同样能改变父表摘要
    private static Set<ResourceLocation> referencedEnchantments(@Nullable CompiledLootTable compiled) {
        return compiled == null ? Set.of() : compiled.referencedEnchantments();
    }

    /**
     * 该节点摘要要吃到的附魔集合——JSON 里被引用的（五个读附魔的机制）+ **本表注入边门槛**引用的。
     * <p>
     * 后者不可省：门槛从"被注入子表自己的池条件"移到"父表注入处"之后，两端 JSON 里都不再出现
     * {@code enchantment} 字段，而它的 {@code max_level} 仍决定模拟用的满级工具（注入场景要带满级
     * 附魔才进得去）。不并入这一项，改 {@code max_level} 就不会让任何表失效，决策 35 那句
     * "影响概率的所有数据变化必然失效"随之破掉。同一份集合同样用于附魔等级旋钮清单。
     */
    private static Set<ResourceLocation> summaryEnchantments(@Nullable CompiledLootTable compiled,
                                                             ResourceLocation node) {
        Set<ResourceLocation> references = referencedEnchantments(compiled);
        Set<ResourceLocation> gates = RuntimeLootLinks.injectionGateEnchantments(node);
        if (gates.isEmpty() || references.containsAll(gates)) {
            return references;
        }
        if (references.isEmpty()) {
            return gates;
        }
        Set<ResourceLocation> union = new HashSet<>(references);
        union.addAll(gates);
        return union;
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

    /** 一轮构建的产物：可发布的当代目录、待入队模拟的基准输入，以及无法模拟的表。 */
    private record BuildResult(CatalogGeneration generation,
                               List<LootProbabilitySimulationWorker.SimulationRequest> uncachedRequests,
                               Set<ResourceLocation> unavailableTables) {
    }
}
