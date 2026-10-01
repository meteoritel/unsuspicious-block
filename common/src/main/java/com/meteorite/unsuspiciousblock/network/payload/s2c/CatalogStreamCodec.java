package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.loottable.analysis.FunctionEffectKind;
import com.meteorite.unsuspiciousblock.loottable.analysis.FunctionFidelity;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler.UncertaintyLevel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootOriginKind;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.simulation.ToolOption;
import com.meteorite.unsuspiciousblock.network.payload.SimulationInputCodec;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ChildTableEntry;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ItemEntry;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ScenarioAssumptions;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ScenarioRef;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.loottable.simulation.FunctionObservationSummary;
import com.meteorite.unsuspiciousblock.loottable.simulation.ObservedFunctionChain;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 目录数据的线格式编解码——<b>全量目录同步</b>与<b>按需结果下发</b>共用这一份实现。
 * <p>
 * 抽出来的理由与项目里其它"唯一实现"边界相同：两条通道传的是同一个 {@link CatalogTableDto}，
 * 各写一份编解码的结果是"改了字段只更新了一处"，而症状会是客户端读到错位的字节流——
 * 那是最难从现象反推成因的一类错误。条件列表的顺序尤其敏感（目录获取路径在条件列表后依次传输
 * {@code functionUncertainty}、{@code luckAffected} 与幸运门槛），因此连字节顺序都由本类独裁。
 * <p>
 * 概率值编码为 1 字节状态 + 按需载荷：{@code Unknown} 带原因枚举、{@code NeedsCondition}
 * 带静态信息性提示、{@code Measured} 带单值或区间、{@code Unreachable} 无载荷。
 * <p>
 * 本轮新增两个字段，都是**追加在各自结构的末尾**，因此新旧读写必须成套发布（同版本客户端/服务端）：
 * 物品条目的 {@code origins} 来源位图（紧随兼容字段 {@code injected} 之后），以及获取路径末尾的
 * **有界静态函数树**（类型 + 本地化描述 + 保真度 + 效果类别 + 函数自身条件 + 子函数 + 有界元数据）。
 * 函数树只传结构化摘要：不传 {@code LootItemFunction} 对象、{@code LootContext}、{@code ItemStack}
 * 或完整原始 JSON（规划 §4.8）。
 */
final class CatalogStreamCodec {
    private CatalogStreamCodec() {
    }

    // ==================== 单表 ====================

    static void writeTable(RegistryFriendlyByteBuf buf, CatalogTableDto table) {
        writeOptions(buf, table.options());
        buf.writeResourceLocation(table.id());
        buf.writeUtf(table.hash());
        buf.writeUtf(Component.Serializer.toJson(table.displayName(), buf.registryAccess()));
        buf.writeUtf(table.type());
        buf.writeVarInt(table.simulationCount());

        buf.writeVarInt(table.childTables().size());
        table.childTables().forEach(buf::writeResourceLocation);

        // 表级场景假设：同一张表的所有物品与子表共用，只发一次
        buf.writeVarInt(table.scenarios().size());
        for (ScenarioAssumptions scenario : table.scenarios()) {
            buf.writeUtf(scenario.scenarioKey());
            writeConditionList(buf, scenario.assumptions());
        }

        buf.writeVarInt(table.items().size());
        for (ItemEntry item : table.items()) {
            buf.writeResourceLocation(item.id());
            buf.writeUtf(Component.Serializer.toJson(item.displayName(), buf.registryAccess()));
            buf.writeBoolean(item.tooltipHint() != null);
            if (item.tooltipHint() != null) {
                buf.writeUtf(Component.Serializer.toJson(item.tooltipHint(), buf.registryAccess()));
            }
            writeProbability(buf, item.probability());
            buf.writeUtf(item.signature().toStoredKey());
            buf.writeVarInt(item.acquisitionPaths().size());
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                writePath(buf, path);
            }
            buf.writeBoolean(item.injected());
            // 来源集合追加在注入布尔之后：injected 是兼容投影，origins 才是真实语义（D09）
            writeOrigins(buf, item.origins());
            writeScenarioRefs(buf, item.scenarioProbabilities());
            // 观测摘要追加在最后：它是"当前输入观测到的事实"，与静态规则分开传输（规划 §4.8）
            writeObservedFunctions(buf, item.observedFunctions());
        }

        buf.writeVarInt(table.childProbabilities().size());
        for (ChildTableEntry child : table.childProbabilities()) {
            buf.writeResourceLocation(child.tableId());
            writeProbability(buf, child.probability());
            writeScenarioRefs(buf, child.scenarioProbabilities());
            // 条件树追加在最后：条件列表的顺序由本类独裁，追加字段必须同步升协议版本
            writeConditionList(buf, child.conditions());
        }
        buf.writeVarInt(table.branches().size());
        for (var branch : table.branches()) {
            buf.writeUtf(branch.kind());
            buf.writeUtf(branch.target());
            writeConditionList(buf, branch.conditions());
            writeConditionList(buf, branch.requirements());
            writeLuckGate(buf, branch.luck());
            buf.writeCollection(branch.activeScenes(), (output, scene) -> output.writeUtf(scene));
            buf.writeBoolean(branch.uncertain());
            buf.writeUtf(branch.injectionSource());
            buf.writeUtf(branch.injectionMode());
        }
    }

    static CatalogTableDto readTable(RegistryFriendlyByteBuf buf) {
        SimulationOptions options = readOptions(buf);
        ResourceLocation tableId = buf.readResourceLocation();
        String hash = buf.readUtf();
        Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
        String type = buf.readUtf();
        int simulationCount = buf.readVarInt();

        int childCount = buf.readVarInt();
        List<ResourceLocation> childTables = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            childTables.add(buf.readResourceLocation());
        }

        int scenarioCount = buf.readVarInt();
        List<ScenarioAssumptions> scenarios = new ArrayList<>(scenarioCount);
        for (int i = 0; i < scenarioCount; i++) {
            scenarios.add(new ScenarioAssumptions(buf.readUtf(), readConditionList(buf)));
        }

        int itemCount = buf.readVarInt();
        List<ItemEntry> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            ResourceLocation itemId = buf.readResourceLocation();
            Component itemName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            Component tooltipHint = buf.readBoolean()
                    ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()) : null;
            Probability probability = readProbability(buf);
            LootResultSignature signature = LootResultSignature.fromStoredKey(buf.readUtf());
            if (signature == null) {
                signature = LootResultSignature.plain(itemId);
            }
            int pathCount = buf.readVarInt();
            List<LootAcquisitionPath> paths = new ArrayList<>(pathCount);
            for (int k = 0; k < pathCount; k++) {
                paths.add(readPath(buf));
            }
            boolean injected = buf.readBoolean();
            Set<LootOriginKind> origins = readOrigins(buf);
            List<ScenarioRef> scenarioRefs = readScenarioRefs(buf);
            FunctionObservationSummary observedFunctions = readObservedFunctions(buf);
            items.add(new ItemEntry(itemId, itemName, tooltipHint, probability,
                    signature, paths, injected, scenarioRefs, origins, observedFunctions));
        }

        int childProbabilityCount = buf.readVarInt();
        List<ChildTableEntry> childProbabilities = new ArrayList<>(childProbabilityCount);
        for (int i = 0; i < childProbabilityCount; i++) {
            childProbabilities.add(new ChildTableEntry(
                    buf.readResourceLocation(), readProbability(buf), readScenarioRefs(buf),
                    readConditionList(buf)));
        }
        int branchCount = buf.readVarInt();
        List<com.meteorite.unsuspiciousblock.loottable.catalog.ScenarioBranch> branches = new ArrayList<>(branchCount);
        for (int index = 0; index < branchCount; index++) {
            branches.add(new com.meteorite.unsuspiciousblock.loottable.catalog.ScenarioBranch(
                    buf.readUtf(), buf.readUtf(), readConditionList(buf), readConditionList(buf), readLuckGate(buf),
                    buf.readList(input -> input.readUtf()), buf.readBoolean(), buf.readUtf(), buf.readUtf()));
        }
        return new CatalogTableDto(tableId, hash, displayName, type, simulationCount,
                childTables, scenarios, items, childProbabilities, options, branches);
    }

    private static void writeOptions(RegistryFriendlyByteBuf buf, @Nullable SimulationOptions options) {
        buf.writeBoolean(options != null);
        if (options == null) return;
        buf.writeVarLong(options.generation());
        buf.writeVarInt(options.scenes().size());
        for (var scene : options.scenes()) {
            buf.writeUtf(scene.scenarioKey());
            writeConditionList(buf, scene.assumptions());
        }
        buf.writeVarInt(options.tools().size());
        for (var tool : options.tools()) {
            buf.writeResourceLocation(tool.id());
            buf.writeUtf(Component.Serializer.toJson(tool.displayName(), buf.registryAccess()));
            buf.writeBoolean(tool.predicateText() != null);
            if (tool.predicateText() != null)
                buf.writeUtf(Component.Serializer.toJson(tool.predicateText(), buf.registryAccess()));
        }
        buf.writeVarInt(options.enchantments().size());
        options.enchantments().forEach((id, level) -> {
            buf.writeResourceLocation(id);
            buf.writeVarInt(level);
        });
        buf.writeVarInt(options.samples().size());
        options.samples().forEach(buf::writeVarInt);
        buf.writeVarInt(options.truncated());
        buf.writeBoolean(options.budgetExhausted());
        buf.writeBoolean(options.toolSelectionAllowed());
    }

    private static @Nullable SimulationOptions readOptions(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        long generation = buf.readVarLong();
        int count = SimulationInputCodec.count(buf, 32);
        List<ScenarioAssumptions> scenes = new ArrayList<>();
        for (int i = 0; i < count; i++)
            scenes.add(new ScenarioAssumptions(buf.readUtf(), readConditionList(buf)));
        count = SimulationInputCodec.count(buf, 4096);
        List<ToolOption> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) tools.add(new ToolOption(buf.readResourceLocation(),
                Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()),
                buf.readBoolean() ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()) : null));
        count = SimulationInputCodec.count(buf, 256);
        Map<ResourceLocation, Integer> enchantments = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) enchantments.put(buf.readResourceLocation(), buf.readVarInt());
        count = SimulationInputCodec.count(buf, 3);
        List<Integer> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) samples.add(buf.readVarInt());
        return new SimulationOptions(generation, scenes, tools, enchantments, samples,
                buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
    }

    private static void writePath(RegistryFriendlyByteBuf buf, LootAcquisitionPath path) {
        buf.writeBoolean(path.sourceChildTable() != null);
        if (path.sourceChildTable() != null) {
            buf.writeResourceLocation(path.sourceChildTable());
        }
        buf.writeBoolean(path.sourceItemTag() != null);
        if (path.sourceItemTag() != null) {
            buf.writeResourceLocation(path.sourceItemTag());
        }
        writeConditionList(buf, path.entryConditions());
        writeConditionList(buf, path.inheritedConditions());
        buf.writeEnum(path.functionUncertainty());
        buf.writeBoolean(path.luckAffected());
        writeLuckGate(buf, path.luckGate());
        // 静态函数树追加在最后：条件列表与新字段的顺序由本类独裁，追加字段必须同步升协议版本
        writeFunctionList(buf, path.functions());
    }

    private static LootAcquisitionPath readPath(RegistryFriendlyByteBuf buf) {
        ResourceLocation sourceChildTable = buf.readBoolean() ? buf.readResourceLocation() : null;
        ResourceLocation sourceItemTag = buf.readBoolean() ? buf.readResourceLocation() : null;
        List<LootConditionInfo> entryConditions = readConditionList(buf);
        List<LootConditionInfo> inheritedConditions = readConditionList(buf);
        return new LootAcquisitionPath(sourceChildTable, sourceItemTag, entryConditions,
                inheritedConditions, buf.readEnum(UncertaintyLevel.class), buf.readBoolean(),
                readLuckGate(buf), List.of(), readFunctionList(buf));
    }

    private static void writeScenarioRefs(RegistryFriendlyByteBuf buf, List<ScenarioRef> refs) {
        buf.writeVarInt(refs.size());
        for (ScenarioRef ref : refs) {
            buf.writeUtf(ref.scenarioKey());
            writeProbability(buf, ref.probability());
        }
    }

    private static List<ScenarioRef> readScenarioRefs(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ScenarioRef> refs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            refs.add(new ScenarioRef(buf.readUtf(), readProbability(buf)));
        }
        return List.copyOf(refs);
    }

    // ==================== 概率与提示 ====================

    static void writeProbability(RegistryFriendlyByteBuf buf, Probability probability) {
        switch (probability) {
            case Probability.Unknown unknown -> {
                buf.writeByte(0);
                buf.writeEnum(unknown.reason());
            }
            case Probability.Unreachable ignored -> buf.writeByte(1);
            case Probability.Measured measured -> {
                buf.writeByte(2);
                buf.writeDouble(measured.lower());
                buf.writeBoolean(measured.upper().isPresent());
                measured.upper().ifPresent(buf::writeDouble);
            }
            case Probability.NeedsCondition needsCondition -> {
                buf.writeByte(3);
                writePathHints(buf, needsCondition.hints());
            }
        }
    }

    static Probability readProbability(RegistryFriendlyByteBuf buf) {
        return switch (buf.readByte()) {
            case 0 -> Probability.unknown(buf.readEnum(UnknownReason.class));
            case 1 -> Probability.unreachable();
            case 3 -> Probability.needsCondition(readPathHints(buf));
            default -> {
                double lower = buf.readDouble();
                if (!buf.readBoolean()) {
                    yield Probability.measured(lower);
                }
                yield Probability.measuredRange(lower, buf.readDouble());
            }
        };
    }

    private static void writePathHints(RegistryFriendlyByteBuf buf, List<PathHint> hints) {
        buf.writeVarInt(hints.size());
        for (PathHint hint : hints) {
            switch (hint) {
                case PathHint.ReferencesParameter parameter -> {
                    buf.writeByte(0);
                    buf.writeEnum(parameter.kind());
                    buf.writeBoolean(parameter.detail() != null);
                    if (parameter.detail() != null) {
                        buf.writeUtf(Component.Serializer.toJson(parameter.detail(), buf.registryAccess()));
                    }
                }
                case PathHint.ReferencesScenario scenario -> {
                    buf.writeByte(1);
                    writeConditionList(buf, scenario.conditions());
                }
                case PathHint.UnresolvedConditions unresolved -> {
                    buf.writeByte(2);
                    writeConditionList(buf, unresolved.conditions());
                }
            }
        }
    }

    private static List<PathHint> readPathHints(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PathHint> hints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte kind = buf.readByte();
            if (kind == 0) {
                ParameterKind parameterKind = buf.readEnum(ParameterKind.class);
                Component detail = buf.readBoolean()
                        ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()) : null;
                hints.add(new PathHint.ReferencesParameter(parameterKind, detail));
                continue;
            }
            if (kind == 1) {
                hints.add(new PathHint.ReferencesScenario(readConditionList(buf)));
            } else if (kind == 2) {
                hints.add(new PathHint.UnresolvedConditions(readConditionList(buf)));
            } else {
                throw new IllegalArgumentException("未知路径提示类型: " + kind);
            }
        }
        return List.copyOf(hints);
    }

    // 逐路径幸运门槛：只在门槛非空（即与幸运无关的路径除外）时多写 1 字节 + 可选数值
    private static void writeLuckGate(RegistryFriendlyByteBuf buf, @Nullable LuckGate gate) {
        buf.writeBoolean(gate != null);
        if (gate == null) {
            return;
        }
        buf.writeBoolean(gate.impossible());
        buf.writeBoolean(gate.minLuck().isPresent());
        gate.minLuck().ifPresent(buf::writeDouble);
        buf.writeBoolean(gate.rangeLimited());
        buf.writeBoolean(gate.bonusRollsGate().isPresent());
        gate.bonusRollsGate().ifPresent(buf::writeDouble);
    }

    @Nullable
    private static LuckGate readLuckGate(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        boolean impossible = buf.readBoolean();
        OptionalDouble minLuck = buf.readBoolean()
                ? OptionalDouble.of(buf.readDouble()) : OptionalDouble.empty();
        boolean rangeLimited = buf.readBoolean();
        OptionalDouble bonusRollsGate = buf.readBoolean()
                ? OptionalDouble.of(buf.readDouble()) : OptionalDouble.empty();
        return new LuckGate(impossible, minLuck, rangeLimited, bonusRollsGate);
    }

    // ==================== 函数观测摘要 ====================

    // 单结果最多 16 条观测链、每条链最多 64 个节点——与捕获预算和存档读端同源，
    // 让"有界结构化摘要"三处（捕获 / 存档 / 网络）用同一组上限
    private static final int MAX_OBSERVED_CHAINS = 16;
    private static final int MAX_CHAIN_NODES = 64;

    private static void writeObservedFunctions(RegistryFriendlyByteBuf buf,
                                               @Nullable FunctionObservationSummary summary) {
        buf.writeBoolean(summary != null);
        if (summary == null) {
            return;
        }
        List<ObservedFunctionChain> chains = summary.chains().size() > MAX_OBSERVED_CHAINS
                ? summary.chains().subList(0, MAX_OBSERVED_CHAINS)
                : summary.chains();
        buf.writeVarInt(chains.size());
        for (ObservedFunctionChain chain : chains) {
            List<ResourceLocation> functions = chain.functionTypes().size() > MAX_CHAIN_NODES
                    ? chain.functionTypes().subList(0, MAX_CHAIN_NODES)
                    : chain.functionTypes();
            buf.writeVarInt(functions.size());
            for (ResourceLocation functionId : functions) {
                buf.writeResourceLocation(functionId);
            }
            buf.writeEnum(chain.state());
            buf.writeBoolean(chain.contentExpanded());
        }
        buf.writeBoolean(summary.truncated());
        buf.writeBoolean(summary.incomplete());
        buf.writeBoolean(summary.unavailable());
    }

    @Nullable
    private static FunctionObservationSummary readObservedFunctions(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        int chainCount = SimulationInputCodec.count(buf, MAX_OBSERVED_CHAINS);
        List<ObservedFunctionChain> chains = new ArrayList<>(chainCount);
        for (int i = 0; i < chainCount; i++) {
            int functionCount = SimulationInputCodec.count(buf, MAX_CHAIN_NODES);
            List<ResourceLocation> functions = new ArrayList<>(functionCount);
            for (int k = 0; k < functionCount; k++) {
                functions.add(buf.readResourceLocation());
            }
            chains.add(new ObservedFunctionChain(functions,
                    buf.readEnum(ObservedFunctionChain.State.class), buf.readBoolean()));
        }
        return new FunctionObservationSummary(chains, buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean());
    }

    // ==================== 来源集合 ====================

    // 来源集合按位图编码：枚举顺序变化会让旧客户端读错来源，因此只追加、不重排
    private static void writeOrigins(RegistryFriendlyByteBuf buf, Set<LootOriginKind> origins) {
        int mask = 0;
        for (LootOriginKind kind : origins) {
            mask |= 1 << kind.ordinal();
        }
        buf.writeVarInt(mask);
    }

    private static Set<LootOriginKind> readOrigins(RegistryFriendlyByteBuf buf) {
        int mask = buf.readVarInt();
        Set<LootOriginKind> origins = EnumSet.noneOf(LootOriginKind.class);
        for (LootOriginKind kind : LootOriginKind.values()) {
            if ((mask & (1 << kind.ordinal())) != 0) {
                origins.add(kind);
            }
        }
        return origins;
    }

    // ==================== 函数树 ====================

    // 单条路径最多 64 个函数节点、嵌套最多 16 层、元数据最多 32 项——与捕获预算同源，
    // 保证"有界结构化摘要"：编解码两侧都不接受无界输入
    private static final int MAX_FUNCTIONS_PER_PATH = 64;
    private static final int MAX_FUNCTION_DEPTH = 16;
    private static final int MAX_FUNCTION_METADATA = 32;

    private static void writeFunctionList(RegistryFriendlyByteBuf buf, List<LootFunctionInfo> functions) {
        int size = Math.min(functions.size(), MAX_FUNCTIONS_PER_PATH);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            LootFunctionInfo info = functions.get(i);
            if (i == size - 1 && functions.size() > size) {
                info = info.withMetadata(LootFunctionInfo.METADATA_TRUNCATED, "siblings");
            }
            writeFunction(buf, info, 0);
        }
    }

    private static List<LootFunctionInfo> readFunctionList(RegistryFriendlyByteBuf buf) {
        int count = SimulationInputCodec.count(buf, MAX_FUNCTIONS_PER_PATH);
        List<LootFunctionInfo> functions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            functions.add(readFunction(buf, 0));
        }
        return List.copyOf(functions);
    }

    // 递归写函数节点；到达深度上限时不再写子节点（读端按同样的上限解释）
    private static void writeFunction(RegistryFriendlyByteBuf buf, LootFunctionInfo info, int depth) {
        buf.writeResourceLocation(info.functionType());
        buf.writeUtf(Component.Serializer.toJson(info.description(), buf.registryAccess()));
        buf.writeEnum(info.fidelity());
        buf.writeEnum(info.effect());
        writeConditionList(buf, info.conditions());
        // 子节点必须与读端同界：读端按 LootFunctionInfo.MAX_CHILDREN_PER_NODE 校验长度并拒绝越界。
        // 写端若不裁剪，一个合法的 65 个子函数的 sequence 就会让客户端整包解码失败。
        List<LootFunctionInfo> children = depth >= MAX_FUNCTION_DEPTH
                ? List.of()
                : info.children();
        Map<String, String> metadata = info.metadata();
        if (depth >= MAX_FUNCTION_DEPTH && !info.children().isEmpty()) {
            Map<String, String> marked = new LinkedHashMap<>(metadata);
            marked.put(LootFunctionInfo.METADATA_TRUNCATED, "depth");
            metadata = marked;
        }
        if (children.size() > LootFunctionInfo.MAX_CHILDREN_PER_NODE) {
            children = children.subList(0, LootFunctionInfo.MAX_CHILDREN_PER_NODE);
            Map<String, String> marked = new LinkedHashMap<>(metadata);
            marked.put(LootFunctionInfo.METADATA_TRUNCATED, "children");
            metadata = marked;
        }
        buf.writeVarInt(children.size());
        for (LootFunctionInfo child : children) {
            writeFunction(buf, child, depth + 1);
        }
        writeFunctionMetadata(buf, metadata);
    }

    private static LootFunctionInfo readFunction(RegistryFriendlyByteBuf buf, int depth) {
        ResourceLocation functionType = buf.readResourceLocation();
        Component description = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
        FunctionFidelity fidelity = buf.readEnum(FunctionFidelity.class);
        FunctionEffectKind effect = buf.readEnum(FunctionEffectKind.class);
        List<LootConditionInfo> conditions = readConditionList(buf);
        int childCount = SimulationInputCodec.count(buf, LootFunctionInfo.MAX_CHILDREN_PER_NODE);
        List<LootFunctionInfo> children = new ArrayList<>(depth >= MAX_FUNCTION_DEPTH ? 0 : childCount);
        for (int i = 0; i < childCount; i++) {
            LootFunctionInfo child = readFunction(buf, depth + 1);
            if (depth < MAX_FUNCTION_DEPTH) {
                children.add(child);
            }
        }
        Map<String, String> metadata = readFunctionMetadata(buf);
        return new LootFunctionInfo(functionType, description, fidelity, effect, conditions, children, metadata);
    }

    private static void writeFunctionMetadata(RegistryFriendlyByteBuf buf, Map<String, String> metadata) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(metadata.entrySet());
        int size = Math.min(entries.size(), MAX_FUNCTION_METADATA);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Map.Entry<String, String> entry = entries.get(i);
            buf.writeUtf(entry.getKey(), 64);
            buf.writeUtf(entry.getValue(), 512);
        }
    }

    private static Map<String, String> readFunctionMetadata(RegistryFriendlyByteBuf buf) {
        int count = SimulationInputCodec.count(buf, MAX_FUNCTION_METADATA);
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            metadata.put(buf.readUtf(64), buf.readUtf(512));
        }
        return metadata;
    }

    // ==================== 条件树 ====================

    static void writeConditionList(RegistryFriendlyByteBuf buf, List<LootConditionInfo> conditions) {
        buf.writeVarInt(conditions.size());
        for (LootConditionInfo condition : conditions) {
            writeCondition(buf, condition);
        }
    }

    static List<LootConditionInfo> readConditionList(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<LootConditionInfo> conditions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            conditions.add(readCondition(buf));
        }
        return conditions;
    }

    private static void writeCondition(RegistryFriendlyByteBuf buf, LootConditionInfo info) {
        buf.writeResourceLocation(info.conditionType());
        buf.writeUtf(Component.Serializer.toJson(info.description(), buf.registryAccess()));
        buf.writeBoolean(info.probability() != null);
        if (info.probability() != null) {
            buf.writeFloat(info.probability());
        }
        buf.writeVarInt(info.children().size());
        for (LootConditionInfo child : info.children()) {
            writeCondition(buf, child);
        }
        buf.writeVarInt(info.metadata().size());
        info.metadata().forEach((key, value) -> {
            buf.writeUtf(key);
            buf.writeUtf(value);
        });
    }

    private static LootConditionInfo readCondition(RegistryFriendlyByteBuf buf) {
        ResourceLocation conditionType = buf.readResourceLocation();
        Component description = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
        Float probability = buf.readBoolean() ? buf.readFloat() : null;
        int childCount = buf.readVarInt();
        List<LootConditionInfo> children = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            children.add(readCondition(buf));
        }
        int metadataCount = buf.readVarInt();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 0; i < metadataCount; i++) {
            metadata.put(buf.readUtf(), buf.readUtf());
        }
        return new LootConditionInfo(conditionType, description, probability, children, metadata);
    }

    // ==================== 分类结构 ====================

    static void writeStructure(RegistryFriendlyByteBuf buf, CatalogStructure structure) {
        buf.writeVarInt(structure.categories().size());
        for (CatalogCategoryDefinition category : structure.categories()) {
            buf.writeResourceLocation(category.id());
            buf.writeUtf(category.translationKey());
            buf.writeUtf(category.fallbackName());
            buf.writeUtf(category.descriptionKey());
            buf.writeUtf(category.descriptionFallback());
            buf.writeResourceLocation(category.iconItem());
            buf.writeVarInt(category.order());
        }
        buf.writeVarInt(structure.rootCategories().size());
        structure.rootCategories().forEach((tableId, categoryId) -> {
            buf.writeResourceLocation(tableId);
            buf.writeResourceLocation(categoryId);
        });
    }

    static CatalogStructure readStructure(RegistryFriendlyByteBuf buf) {
        int categoryCount = buf.readVarInt();
        List<CatalogCategoryDefinition> categories = new ArrayList<>(categoryCount);
        for (int i = 0; i < categoryCount; i++) {
            categories.add(new CatalogCategoryDefinition(buf.readResourceLocation(), buf.readUtf(),
                    buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readResourceLocation(), buf.readVarInt()));
        }
        int rootCount = buf.readVarInt();
        Map<ResourceLocation, ResourceLocation> roots = new LinkedHashMap<>();
        for (int i = 0; i < rootCount; i++) {
            roots.put(buf.readResourceLocation(), buf.readResourceLocation());
        }
        return new CatalogStructure(categories, roots);
    }
}
