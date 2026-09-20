package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootMechanismSupport;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulatedValue;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.loottable.signature.SignatureExcludedComponents;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootConditionFingerprint;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.loottable.simulation.PathHintAnalyzer;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationConstraintCatalog;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationMeasurement;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationProfile;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenario;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.LootProbabilityData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
     */
    private static final String SIMULATION_CACHE_VERSION = "loot-analysis-v19";

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
     * 失败时抛出，由 {@link #ensureLoaded} 决定保留上一代还是进入空状态。
     */
    private static BuildResult buildGeneration(MinecraftServer server) {
        long generation = ++generationCounter;

        // 1. 捕获本轮资源快照（全表有效原文 + 完整资源栈），编译与哈希共用同一次读盘
        LootTableSourceSnapshot sourceSnapshot = LootTableSourceSnapshot.capture(server.getResourceManager());

        // 2. 建图 → 编译 → 投影 → 组装静态读模型
        ArchaeologyJournalCatalog.LoadResult loadResult = ArchaeologyJournalCatalog.load(
                generation, sourceSnapshot, server.getResourceManager(), server.registryAccess());
        Map<ResourceLocation, TableDefinition> staticTables = loadResult.staticTables();
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
        if (!unavailableReasons.isEmpty()) {
            Map<ResourceLocation, TableDefinition> marked = new LinkedHashMap<>(staticTables);
            unavailableReasons.keySet().forEach(tableId ->
                    marked.put(tableId, markUnparsed(marked.get(tableId))));
            staticTables = Map.copyOf(marked);
            LOGGER.warn("{} 张战利品表无法被本模组模拟，已按不可用上报（不做猜测）：{}",
                    unavailableReasons.size(), unavailableReasons);
        }

        // 4. 计算哈希（吃子树内每张表的资源栈摘要、编译产物摘要与被引用附魔定义摘要）
        Map<ResourceLocation, String> tableHashes = computeTableHashes(
                loadResult.session().referenceGraph(), staticTables,
                loadResult.session().compiledTables(), server.registryAccess());

        // 5. 为全部可模拟表算好约束描述（含场景规划）——启动只跑基准输入，而"基准输入是哪一个"
        //    需要先知道基准场景的条件赋值，因此这一步是缓存查询的前置条件而不是可省的预计算。
        ServerLevel level = server.overworld();
        Map<ResourceLocation, SimulationConstraintCatalog> constraintCatalogs = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            if (unavailableReasons.containsKey(entry.getKey())) {
                continue;
            }
            SimulationConstraintCatalog constraint = buildConstraintCatalog(
                    loadResult.session(), entry.getKey(), entry.getValue(), level);
            constraintCatalogs.put(entry.getKey(), constraint);
            // 只为"有信息量"的表留一行：单场景且无截断的表没什么可说的，逐表刷屏会淹掉真正的异常
            if (constraint.scenarios().size() > 1 || constraint.truncatedScenarioCount() > 0
                    || constraint.scenarioBudgetExhausted()) {
                LOGGER.info("战利品表 {} 的模拟约束：{}；可见旋钮 {}",
                        entry.getKey(), constraint.describe(), constraint.parameterKinds());
            }
            if (constraint.scenarioBudgetExhausted()) {
                LOGGER.warn("战利品表 {} 的条件树展开超预算，超出部分已按无约束处理（数值偏保守）",
                        entry.getKey());
            }
        }
        CatalogGeneration catalogGeneration = new CatalogGeneration(
                loadResult.session(), loadResult.structure(), staticTables, tableHashes, constraintCatalogs);

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
            LootTableAnalysisSession session, ResourceLocation tableId, TableDefinition table,
            ServerLevel level) {
        SimulationScenarioPlanner.ScenarioPlan plan =
                SimulationScenarioPlanner.plan(tableId, table, level);
        SimulationProfile baseProfile = SimulationProfile.eligibleConditions(level, table.type());

        Map<ResourceLocation, String> tools = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> enchantmentLevels = new LinkedHashMap<>();
        HolderLookup.RegistryLookup<Enchantment> enchantments =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (ResourceLocation node : session.referenceGraph().descendantsInclusive(tableId)) {
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
        Map<ResourceLocation, List<LootConditionInfo>> childEntryGates = new LinkedHashMap<>();
        for (ResourceLocation childTable : table.childTables()) {
            // 注入边的门槛只有 common 侧那一份声明，不在任何 JSON 里；在这里翻成条件树描述，
            // 父表页就能说明"进这张子表需要什么"，而不是只给一个没有原因的「未命中」
            RuntimeLootLinks.injectionGate(childTable).ifPresent(gate -> childEntryGates.put(childTable,
                    SimulationConstraintCatalog.describeGate(gate, level.registryAccess())));
        }
        return SimulationConstraintCatalog.build(plan, baseProfile, tools, enchantmentLevels,
                childEntryGates);
    }

    /** 该表当前的每表内容哈希（客户端按需请求与目录下发共用）；未收录时为空串。 */
    public static String getTableHash(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? "" : generation.tableHash(tableId);
    }

    // 不可用表的条目一律标记为「规则未解析」：这是与"未覆盖""尚未计算"都不同的失败原因，
    // 玩家据此知道该表的规则本模组读不了，而不是自己的处境问题
    private static TableDefinition markUnparsed(TableDefinition table) {
        List<ItemDefinition> items = table.items().stream()
                .map(item -> new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                        Probability.unknown(UnknownReason.UNPARSED), item.signature(),
                        item.acquisitionPaths(), item.injected(), List.of()))
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
            broadcastCatalogHash(generationId, server);
            return;
        }

        installHandlers(worker, generationId);
        worker.enqueueBatch(uncached);
    }

    /**
     * 安装工作器的两个回调：结果提交与队列排空广播。
     * <p>
     * 结果回调携带 {@code requester}：为 {@code null} 的是启动批量填充，结果要发布到共享目录；
     * 非空的是玩家的按需请求，结果只回给请求者——按内容去重的缓存是全服共享的，但
     * "当前展示哪个输入"是每个玩家自己的选择，把它写进共享目录会让两个玩家互相覆盖对方的界面。
     */
    private static void installHandlers(LootProbabilitySimulationWorker worker, long generationId) {
        worker.setResultHandler(ArchaeologyJournalServerCatalog::commitSimulated);
        worker.setQueueDrainedHandler(srv -> broadcastCatalogHash(generationId, srv));
    }

    // 同步回退模拟（仅在 worker 未启动时使用）
    private static void simulateSynchronously(MinecraftServer server,
                                             List<LootProbabilitySimulationWorker.SimulationRequest> requests) {
        LootProbabilityData probabilityData = LootProbabilityData.get(server.overworld());
        CatalogGeneration generation = currentGeneration;
        if (generation == null) {
            return;
        }
        for (LootProbabilitySimulationWorker.SimulationRequest request : requests) {
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(
                    request.tableId(), request.rawTable(), server.overworld(),
                    request.input(), request.scenario());
            commitSimulated(result, generation.generation(), request.requester(), server, probabilityData);
        }
    }

    /**
     * 服务端处理一份按需模拟请求——校验、入队。回执与限流由调用方（网络层）负责。
     * <p>
     * 三条校验（决策 15/36）：表仍在追踪、哈希未变（变了说明这份输入属于上一版内容）、
     * 输入由当前目录签发（场景、工具、附魔等级、抽样次数逐项落在约束内）。
     */
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

        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(
                    tableId, raw, player.server.overworld(), input, scenario);
            commitSimulated(result, generation.generation(), null, player.server);
            sendScenarioResult(player, tableId, hash, input);
            return new OnDemandResult(OnDemandOutcome.SIMULATED_INLINE, input);
        }
        installHandlers(worker, generation.generation());
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
        installHandlers(worker, generation.generation());
        worker.enqueuePriority(new LootProbabilitySimulationWorker.SimulationRequest(
                tableId, raw, constraint.baselineInput(), constraint.baselineScenario(),
                generation.generation(), generation.tableHash(tableId), null));
    }

    /** 按需请求的受理结果——{@code input} 非空时调用方可以拿它生成回执里的输入键。 */
    public record OnDemandResult(OnDemandOutcome outcome, @Nullable SimulationInput input) {
    }

    /** 按需模拟请求的受理结果。 */
    public enum OnDemandOutcome {
        /** 已入队，结果随后由 {@code SyncScenarioResultPayload} 下发。 */
        QUEUED,
        /** worker 未启动，已在主线程同步算完并下发。 */
        SIMULATED_INLINE,
        /** 表未收录或不可用。 */
        UNKNOWN_TABLE,
        /** 客户端持有的表哈希已过期，需要先重新同步目录。 */
        STALE_HASH,
        /** 输入未被当前目录签发（自造参数、超界幸运、未签发的档位）。 */
        REJECTED_INPUT,
        /** 玩家请求队列已满。 */
        REJECTED_QUEUE_FULL
    }

    /** 当代目录代次；未加载时为 0（回执里用它让客户端丢弃过期消息）。 */
    public static long currentGenerationId() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? 0L : generation.generation();
    }

    // 带 generation 校验的提交入口：旧代结果直接丢弃
    private static void commitSimulated(LootProbabilitySimulator.SimResult result, long generationId,
                                        @Nullable UUID requester, MinecraftServer server) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.generation() != generationId) {
            LOGGER.warn("丢弃来自旧 generation {} 的战利品表 {} 输入结果（当前 generation {}）",
                    generationId, result.tableId(),
                    generation == null ? "无" : generation.generation());
            return;
        }
        commitSimulated(result, generationId, requester, server, LootProbabilityData.get(server.overworld()));
    }

    // 实际提交：写缓存 → （启动）发布到共享目录 或 （按需）只回给请求者
    private static void commitSimulated(LootProbabilitySimulator.SimResult result, long generationId,
                                        @Nullable UUID requester, MinecraftServer server,
                                        LootProbabilityData probabilityData) {
        if (!result.successful()) {
            LOGGER.warn("忽略战利品表 {} 输入 {} 的失败模拟结果；其概率保持未知，不写入缓存，"
                            + "将在下次数据包重载时重新尝试",
                    result.tableId(), result.input().key());
            return;
        }
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.generation() != generationId) {
            return;
        }
        ResourceLocation tableId = result.tableId();
        String hash = generation.tableHash(tableId);
        SimulationInput input = result.input();
        String inputKey = input.key();

        // 同一个 (表, 输入) 可能被多次提交（多个等待者各收一份）：测量值只写一次。
        // **但只有内容哈希一致时才允许跳过**——哈希不一致说明存档里那一条属于上一版内容，
        // 此时必须写：putMeasurement 会连带把陈旧条目整体换掉，哈希也才会被更新。
        // 曾经漏掉哈希这一半，于是形成死循环：读路径判"哈希不同 → 重算"，写路径却"已有测量值 → 跳过写入"，
        // 哈希永远停在旧值，每次启动都全量重算（实测症状：连续三次启动都是 0 命中 / 58 待模拟，
        // 且存档里的数值仍是修复前的旧口径）。
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
            probabilityData.putMeasurement(tableId, hash, inputKey,
                    new LootProbabilityData.InputMeasurement(input.params().sampleCount(),
                            measurement.itemProbabilities(), measurement.childProbabilities()),
                    discoveredNow);
        }

        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (constraint == null) {
            return;
        }
        TableDefinition derived = deriveTable(generation, tableId, constraint, input, probabilityData);
        if (requester == null) {
            // 启动基准填充：写进共享目录，客户端由此拿到基准数字
            generation.publishSimulated(derived);
            return;
        }
        // 按需请求：只把这一份结果回给请求者（客户端按代次/哈希/输入键校验后自行决定是否切换）
        ServerPlayer player = server.getPlayerList().getPlayer(requester);
        if (player != null) {
            Services.NETWORK.sendToPlayer(player, new SyncScenarioResultPayload(
                    generationId, hash, inputKey, CatalogTableDto.from(derived, hash)));
        }
    }

    // 按需结果下发（worker 未启动的同步回退路径使用）
    private static void sendScenarioResult(ServerPlayer player, ResourceLocation tableId,
                                           String hash, SimulationInput input) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null) {
            return;
        }
        SimulationConstraintCatalog constraint = generation.constraintCatalog(tableId);
        if (constraint == null) {
            return;
        }
        TableDefinition derived = deriveTable(generation, tableId, constraint, input,
                LootProbabilityData.get(player.server.overworld()));
        Services.NETWORK.sendToPlayer(player, new SyncScenarioResultPayload(
                generation.generation(), hash, input.key(), CatalogTableDto.from(derived, hash)));
    }
    // 带 generation 校验的广播入口：旧代排空事件不再触发同步
    private static void broadcastCatalogHash(long generationId, MinecraftServer server) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.generation() != generationId) {
            return;
        }
        String hash = generation.catalogHash();
        if (hash.isEmpty()) return;
        SyncCatalogHashPayload payload = new SyncCatalogHashPayload(hash);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Services.NETWORK.sendToPlayer(player, payload);
        }
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
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? Map.of() : generation.simulatedTables();
    }

    public static CatalogStructure getCatalogStructure() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? CatalogStructure.empty() : generation.structure();
    }

    /** 获取原始目录的只读视图（概率为 "?" 占位符，但物品列表完整）。
     *  ensureLoaded 后立即可用，不受渐进模拟影响；供成就判定等需要完整表集合的场景使用 */
    public static Map<ResourceLocation, TableDefinition> getRawCatalog() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? Map.of() : generation.staticTables();
    }

    /** 获取当代查询索引（子树物品等跨表聚合的唯一入口）；目录尚未加载时返回空索引 */
    public static CatalogQueryIndex getQueryIndex() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? CatalogQueryIndex.EMPTY : generation.queryIndex();
    }

    /** 获取原始表定义（概率为占位符），供工作线程模拟时查询 */
    @Nullable
    public static TableDefinition getRawTable(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? null : generation.staticTable(tableId);
    }

    // 判断指定表是否已被当前服务端的原始目录收录；不受概率模拟进度影响
    public static boolean isTrackedTable(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation != null && generation.isTracked(tableId);
    }

    /** 原始目录中的表总数（已 ensureLoaded 后可用） */
    public static int getRawCatalogCount() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? 0 : generation.trackedTableCount();
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
     * 分场景列表里，非当前输入的场景一律是 {@code Unknown(NOT_SIMULATED)}——"没算过"与"算出来是零"
     * 必须分得开（决策 36）。
     */
    private static TableDefinition deriveTable(CatalogGeneration generation, ResourceLocation tableId,
                                               SimulationConstraintCatalog constraint, SimulationInput input,
                                               LootProbabilityData probabilityData) {
        TableDefinition raw = generation.staticTable(tableId);
        if (raw == null) {
            throw new IllegalStateException("表不在当代目录中: " + tableId);
        }
        String displayedScenarioKey = input.scenarioKey();
        Map<String, LootProbabilityData.InputMeasurement> byScenario = measurementsByScenario(
                tableId, constraint, input.params(), probabilityData);
        LootProbabilityData.InputMeasurement displayed = byScenario.get(displayedScenarioKey);
        Map<String, LootProbabilityData.DiscoveryRecord> discovery = probabilityData.getDiscovery(tableId);

        List<ItemDefinition> items = new ArrayList<>(raw.items().size() + discovery.size());
        Set<String> rawKeys = new HashSet<>();
        for (ItemDefinition item : raw.items()) {
            String storedKey = item.signature().toStoredKey();
            rawKeys.add(storedKey);
            List<PathHint> hints = PathHintAnalyzer.hintsFor(item.acquisitionPaths());
            List<ScenarioProbability> scenarioProbabilities = itemScenarioProbabilities(
                    constraint, storedKey, hints, byScenario);
            Probability display = PathHintAnalyzer.deriveDisplay(
                    displayedValue(displayed, storedKey, constraint, displayedScenarioKey, hints),
                    item.acquisitionPaths());
            items.add(new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                    display, item.signature(), item.acquisitionPaths(), item.injected(),
                    scenarioProbabilities));
        }

        // 动态条目（GLM / LootTableEvents.MODIFY 模拟期注入）：没有静态路径，因此"需要什么条件"
        // 无从陈述，展示值只取实际测量到的那一份；它们的"来源"记录挂在表级、不随 LRU 淘汰，
        // 所以即使某个输入的测量值被淘汰，条目本身也不会从网格里消失（决策 37）。
        for (Map.Entry<String, LootProbabilityData.DiscoveryRecord> entry : discovery.entrySet()) {
            if (rawKeys.contains(entry.getKey())) {
                continue;
            }
            LootResultSignature signature = LootResultSignature.fromStoredKey(entry.getKey());
            if (signature == null) {
                continue;
            }
            List<LootAcquisitionPath> paths = discoveredPaths(entry.getValue());
            List<ScenarioProbability> scenarioProbabilities = discoveredScenarioProbabilities(
                    constraint, entry.getKey(), byScenario);
            Probability display = displayedValue(displayed, entry.getKey(), constraint,
                    displayedScenarioKey, List.of());
            ItemDefinition discoveredItem = LootTableCatalog.buildDiscoveredDefinition(
                    signature, display, true, scenarioProbabilities);
            items.add(paths.isEmpty() ? discoveredItem : new ItemDefinition(
                    discoveredItem.id(), discoveredItem.displayName(), discoveredItem.tooltipHint(),
                    discoveredItem.probability(), discoveredItem.signature(), paths,
                    true, discoveredItem.scenarioProbabilities()));
        }

        List<ChildTableProbability> childProbabilities = new ArrayList<>();
        for (ResourceLocation childTable : raw.childTables()) {
            // 两条来源合成入口的条件树：通往它的路径共同成立的条件（交集）+ 注入边门槛。
            // 交集的理由：tooltip 的头是"父表条件"，用并集会把"只有部分路径需要"的条件说成"需要"。
            // 门槛必须在这里补：它不写在任何 JSON 里，静态投影看不到它，缺了它被入口挡住的子表
            // 只能显示一个没有原因的「未命中」。
            List<LootConditionInfo> entryGates = constraint.childEntryGates()
                    .getOrDefault(childTable, List.of());
            List<LootConditionInfo> conditions = mergeConditions(
                    commonChildConditions(raw, childTable), entryGates);
            // 提示按并集派生：它要回答的是"这个入口引用了哪些可调的旋钮/条件"，比条件树宽松
            List<PathHint> hints = PathHintAnalyzer.hintsForConditions(
                    mergeConditions(childTableConditions(raw, childTable), entryGates));
            List<ScenarioProbability> scenarioProbabilities = new ArrayList<>();
            boolean applicableSomewhere = false;
            for (SimulationScenario scenario : constraint.scenarios()) {
                if (!scenario.applicableChildTables().contains(childTable)) {
                    scenarioProbabilities.add(new ScenarioProbability(scenario.key(),
                            PathHintAnalyzer.inapplicableScenarioDisplay(hints), scenario.assumptions()));
                    continue;
                }
                applicableSomewhere = true;
                LootProbabilityData.InputMeasurement measurement = byScenario.get(scenario.key());
                SimulatedValue measured = measurement == null
                        ? null : measurement.children().get(childTable);
                scenarioProbabilities.add(new ScenarioProbability(scenario.key(),
                        measured == null
                                ? Probability.unknown(UnknownReason.NOT_SIMULATED)
                                : measured.toProbability(UnknownReason.UNCOVERED),
                        scenario.assumptions()));
            }
            if (!applicableSomewhere) {
                scenarioProbabilities = List.of();
            }
            // 展示值走与物品同构的入口派生：零命中且门槛可陈述时报「需要条件」。
            // 分场景列表保留原始测量值（与物品的列表一致）：它是"每个场景各测到多少"的事实表，
            // 而入口这一行回答的是"当前输入下能不能进"。
            childProbabilities.add(new ChildTableProbability(childTable,
                    PathHintAnalyzer.deriveEntryDisplay(
                            scenarioValue(scenarioProbabilities, displayedScenarioKey), hints),
                    List.copyOf(scenarioProbabilities), conditions));
        }

        return new TableDefinition(raw.id(), raw.displayName(), raw.type(), items,
                input.params().sampleCount(), raw.childTables(), childProbabilities);
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
            String inputKey = new SimulationInput(scenario.key(),
                    scenario.profile().conditionOutcomes(), params).key();
            LootProbabilityData.InputMeasurement measurement =
                    probabilityData.getMeasurement(tableId, inputKey);
            if (measurement != null) {
                result.put(scenario.key(), measurement);
            }
        }
        return result;
    }

    // 当前展示输入下某个签名的展示值：测到了就是测量值，适用但没算过是"尚未计算"，
    // 不适用则由静态提示决定「需要条件」还是「未覆盖」。
    // hints 由调用方给出：静态条目传它自己路径的提示，动态条目传空——后者没有静态路径可引用。
    private static Probability displayedValue(
            @Nullable LootProbabilityData.InputMeasurement displayed, String storedKey,
            SimulationConstraintCatalog constraint, String displayedScenarioKey, List<PathHint> hints) {
        SimulatedValue measured = displayed == null ? null : displayed.items().get(storedKey);
        if (measured != null) {
            return measured.toProbability(UnknownReason.UNCOVERED);
        }
        SimulationScenario scenario = constraint.scenario(displayedScenarioKey);
        boolean applicableHere = scenario != null
                && scenario.applicableSignatures().contains(storedKey);
        if (applicableHere) {
            return Probability.unknown(UnknownReason.NOT_SIMULATED);
        }
        return PathHintAnalyzer.inapplicableScenarioDisplay(hints);
    }

    // 静态条目的分场景展示列表；没有任何场景覆盖该签名时返回空列表——
    // 那是"场景被上界截断"，逐个写 0 会把"未覆盖"显示成"不可达"
    private static List<ScenarioProbability> itemScenarioProbabilities(
            SimulationConstraintCatalog constraint, String storedKey,
            List<PathHint> hints, Map<String, LootProbabilityData.InputMeasurement> byScenario) {
        List<ScenarioProbability> result = new ArrayList<>();
        boolean applicableSomewhere = false;
        for (SimulationScenario scenario : constraint.scenarios()) {
            if (!scenario.applicableSignatures().contains(storedKey)) {
                result.add(new ScenarioProbability(scenario.key(),
                        PathHintAnalyzer.inapplicableScenarioDisplay(hints), scenario.assumptions()));
                continue;
            }
            applicableSomewhere = true;
            LootProbabilityData.InputMeasurement measurement = byScenario.get(scenario.key());
            SimulatedValue measured = measurement == null ? null : measurement.items().get(storedKey);
            result.add(new ScenarioProbability(scenario.key(),
                    measured == null
                            ? Probability.unknown(UnknownReason.NOT_SIMULATED)
                            : measured.toProbability(UnknownReason.UNCOVERED),
                    scenario.assumptions()));
        }
        return applicableSomewhere ? List.copyOf(result) : List.of();
    }

    // 动态条目只保留缓存里实际测到过的场景；其余场景不进列表，避免为"没观测到"编造状态
    private static List<ScenarioProbability> discoveredScenarioProbabilities(
            SimulationConstraintCatalog constraint, String storedKey,
            Map<String, LootProbabilityData.InputMeasurement> byScenario) {
        List<ScenarioProbability> result = new ArrayList<>();
        for (SimulationScenario scenario : constraint.scenarios()) {
            LootProbabilityData.InputMeasurement measurement = byScenario.get(scenario.key());
            SimulatedValue measured = measurement == null ? null : measurement.items().get(storedKey);
            if (measured == null) {
                continue;
            }
            result.add(new ScenarioProbability(scenario.key(),
                    measured.toProbability(UnknownReason.UNCOVERED), scenario.assumptions()));
        }
        return List.copyOf(result);
    }

    // 动态条目的获取路径：来源记录在表级发现记录里，直接还原成路径即可
    private static List<LootAcquisitionPath> discoveredPaths(LootProbabilityData.DiscoveryRecord record) {
        List<LootAcquisitionPath> paths = new ArrayList<>(
                record.sourceChildTables().size() + (record.hasDirectSource() ? 1 : 0));
        if (record.hasDirectSource()) {
            paths.add(new LootAcquisitionPath(null, List.of(), List.of()));
        }
        for (ResourceLocation childSource : record.sourceChildTables()) {
            paths.add(new LootAcquisitionPath(childSource, List.of(), List.of()));
        }
        return List.copyOf(paths);
    }

    // 某个场景在分场景列表里的值；场景不在列表里（被截断或尚未规划）时如实报"未覆盖"
    private static Probability scenarioValue(List<ScenarioProbability> probabilities, String scenarioKey) {
        for (ScenarioProbability probability : probabilities) {
            if (probability.scenarioKey().equals(scenarioKey)) {
                return probability.probability();
            }
        }
        return Probability.uncovered();
    }

    // 该子表入口在父表里出现过的条件（**并集**）；用于"这个场景下为什么拿不到"的静态陈述——
    // 提示要回答的是"引用了哪些可调的旋钮/条件"，只要有一条路径引用过就该列出来
    private static List<LootConditionInfo> childTableConditions(
            TableDefinition parent, ResourceLocation childTable) {
        LinkedHashMap<String, LootConditionInfo> conditions = new LinkedHashMap<>();
        for (ItemDefinition item : parent.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!childTable.equals(path.sourceChildTable())) {
                    continue;
                }
                for (LootConditionInfo condition : path.allConditions()) {
                    conditions.putIfAbsent(LootConditionFingerprint.of(condition), condition);
                }
            }
        }
        return List.copyOf(conditions.values());
    }

    // 通往该子表的**全部**路径共同成立的条件（交集）：这是条件树要展示的口径——
    // 用并集会把"只有部分路径需要"的条件说成"要拿到它必须满足"。语义与提示的并集刻意不同。
    private static List<LootConditionInfo> commonChildConditions(
            TableDefinition parent, ResourceLocation childTable) {
        LinkedHashMap<String, LootConditionInfo> common = null;
        for (ItemDefinition item : parent.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!childTable.equals(path.sourceChildTable())) {
                    continue;
                }
                LinkedHashMap<String, LootConditionInfo> indexed = indexConditions(path.allConditions());
                if (common == null) {
                    common = indexed;
                } else {
                    common.keySet().retainAll(indexed.keySet());
                }
            }
        }
        return common == null ? List.of() : List.copyOf(common.values());
    }

    // 与运行时的实例不同，同一条件在两条路径上是两个对象：用指纹做稳定去重键
    private static LinkedHashMap<String, LootConditionInfo> indexConditions(
            List<LootConditionInfo> conditions) {
        LinkedHashMap<String, LootConditionInfo> result = new LinkedHashMap<>();
        for (LootConditionInfo condition : conditions) {
            result.putIfAbsent(LootConditionFingerprint.of(condition), condition);
        }
        return result;
    }

    // 合成入口条件树的两段来源；第二段（注入门槛）排在后面，让"父表自己的条件"先读
    private static List<LootConditionInfo> mergeConditions(List<LootConditionInfo> first,
                                                           List<LootConditionInfo> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        LinkedHashMap<String, LootConditionInfo> merged = indexConditions(first);
        for (LootConditionInfo condition : second) {
            merged.putIfAbsent(LootConditionFingerprint.of(condition), condition);
        }
        return List.copyOf(merged.values());
    }

    // 对每个表计算 SHA-256 哈希：缓存版本 + 该表子树的资源栈摘要、编译产物摘要与被引用附魔定义摘要。
    // 摘要按 descendantsInclusive 覆盖后代每一张表，因此子表引用的 item tag 成员变化
    // （子表 JSON 文本不变、只有 tag 展开结果变）同样会让父表失效。
    //
    // 保证范围拆成两句（决策 35），不要读成一句更大的保证：
    //   1) "表 JSON 变化必然失效"——由资源栈摘要承担，现在成立；
    //   2) "影响概率的所有数据变化必然失效"——加上被引用附魔的定义摘要后成立。
    // 已知残余（不列入本摘要的外部注册表依赖）见 docs/dev/loottable.md。
    private static Map<ResourceLocation, String> computeTableHashes(
            LootTableReferenceGraph graph, Map<ResourceLocation, TableDefinition> tables,
            Map<ResourceLocation, CompiledLootTable> compiledTables, HolderLookup.Provider registries) {
        Map<ResourceLocation, String> hashes = new LinkedHashMap<>();
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

        for (ResourceLocation tableId : tables.keySet()) {
            try {
                digest.reset();
                LootTableSourceSnapshot.updateDigest(digest, SIMULATION_CACHE_VERSION);
                // 排除列表决定物品签名怎么派生，改配置必须让相关表失效（否则会沿用旧签名的缓存结果）
                LootTableSourceSnapshot.updateDigest(digest,
                        String.join(",", SignatureExcludedComponents.configuredIds()));
                graph.updateSubtreeDigest(tableId, digest,
                        (node, nodeDigest) -> {
                            updateCompiledProductDigest(nodeDigest, tables.get(node));
                            // 附魔定义不在任何战利品表 JSON 里，却决定模拟用的满级工具与等级控件范围
                            updateEnchantmentDigest(nodeDigest, registries,
                                    referencedEnchantments(compiledTables.get(node)));
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
     * 被引用附魔的定义摘要——只取 {@code max_level}。
     * <p>
     * 取这个字段而不是整份定义：决定模拟行为的是等级上限（它决定模拟用的满级工具与等级控件范围），
     * 其余字段（anvil 花费、权重等）不影响任何概率。因此本摘要**不覆盖**附魔定义的其它字段——
     * 这一条残余与其余外部注册表依赖一起写进 {@code docs/dev/loottable.md}。
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
