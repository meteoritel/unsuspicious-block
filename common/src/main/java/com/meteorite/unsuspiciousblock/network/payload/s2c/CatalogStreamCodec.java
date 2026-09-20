package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler.UncertaintyLevel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
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
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

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
 */
final class CatalogStreamCodec {
    private CatalogStreamCodec() {
    }

    // ==================== 单表 ====================

    static void writeTable(RegistryFriendlyByteBuf buf, CatalogTableDto table) {
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
            writeScenarioRefs(buf, item.scenarioProbabilities());
        }

        buf.writeVarInt(table.childProbabilities().size());
        for (ChildTableEntry child : table.childProbabilities()) {
            buf.writeResourceLocation(child.tableId());
            writeProbability(buf, child.probability());
            writeScenarioRefs(buf, child.scenarioProbabilities());
            // 条件树追加在最后：条件列表的顺序由本类独裁，追加字段必须同步升协议版本
            writeConditionList(buf, child.conditions());
        }
    }

    static CatalogTableDto readTable(RegistryFriendlyByteBuf buf) {
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
            items.add(new ItemEntry(itemId, itemName, tooltipHint, probability,
                    signature, paths, injected, readScenarioRefs(buf)));
        }

        int childProbabilityCount = buf.readVarInt();
        List<ChildTableEntry> childProbabilities = new ArrayList<>(childProbabilityCount);
        for (int i = 0; i < childProbabilityCount; i++) {
            childProbabilities.add(new ChildTableEntry(
                    buf.readResourceLocation(), readProbability(buf), readScenarioRefs(buf),
                    readConditionList(buf)));
        }
        return new CatalogTableDto(tableId, hash, displayName, type, simulationCount,
                childTables, scenarios, items, childProbabilities);
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
    }

    private static LootAcquisitionPath readPath(RegistryFriendlyByteBuf buf) {
        ResourceLocation sourceChildTable = buf.readBoolean() ? buf.readResourceLocation() : null;
        ResourceLocation sourceItemTag = buf.readBoolean() ? buf.readResourceLocation() : null;
        List<LootConditionInfo> entryConditions = readConditionList(buf);
        List<LootConditionInfo> inheritedConditions = readConditionList(buf);
        return new LootAcquisitionPath(sourceChildTable, sourceItemTag, entryConditions,
                inheritedConditions, buf.readEnum(UncertaintyLevel.class), buf.readBoolean(),
                readLuckGate(buf));
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
            }
        }
    }

    private static List<PathHint> readPathHints(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PathHint> hints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.readByte() == 0) {
                ParameterKind kind = buf.readEnum(ParameterKind.class);
                Component detail = buf.readBoolean()
                        ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()) : null;
                hints.add(new PathHint.ReferencesParameter(kind, detail));
                continue;
            }
            hints.add(new PathHint.ReferencesScenario(readConditionList(buf)));
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
