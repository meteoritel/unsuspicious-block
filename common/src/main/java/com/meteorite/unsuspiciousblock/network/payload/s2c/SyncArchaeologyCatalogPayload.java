package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ChildTableEntry;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ItemEntry;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ScenarioAssumptions;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto.ScenarioRef;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量目录同步包 —— 服务端→客户端。
 * <p>
 * 载荷是 {@link CatalogTableDto} 而非服务端内部记录：场景假设条件树按 {@code scenarioKey}
 * 每表只发一次，物品与子表侧只带 key 与概率（P3-1）。这样把"同一条件树被按物品重复序列化"
 * 的冗余去掉，同时让目录哈希的输入与实际上线内容完全一致。
 */
public record SyncArchaeologyCatalogPayload(List<CatalogTableDto> catalog, CatalogStructure structure)
        implements CustomPacketPayload {

    public static final Type<SyncArchaeologyCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_archaeology_catalog"));

    public SyncArchaeologyCatalogPayload {
        catalog = List.copyOf(catalog);
    }

    @Override
    public @NotNull Type<SyncArchaeologyCatalogPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncArchaeologyCatalogPayload> STREAM_CODEC =
            StreamCodec.of(SyncArchaeologyCatalogPayload::encode, SyncArchaeologyCatalogPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SyncArchaeologyCatalogPayload payload) {
        buf.writeVarInt(payload.catalog.size());
        for (CatalogTableDto table : payload.catalog) {
            buf.writeResourceLocation(table.id());
            buf.writeUtf(Component.Serializer.toJson(table.displayName(), buf.registryAccess()));
            buf.writeUtf(table.type());
            buf.writeVarInt(table.simulationCount());

            buf.writeVarInt(table.childTables().size());
            table.childTables().forEach(buf::writeResourceLocation);

            // 表级场景假设：同一张表的所有物品与子表共用，只发一次
            buf.writeVarInt(table.scenarios().size());
            for (ScenarioAssumptions scenario : table.scenarios()) {
                buf.writeUtf(scenario.scenarioKey());
                encodeConditionList(buf, scenario.assumptions());
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
                    buf.writeBoolean(path.sourceChildTable() != null);
                    if (path.sourceChildTable() != null) {
                        buf.writeResourceLocation(path.sourceChildTable());
                    }
                    buf.writeBoolean(path.sourceItemTag() != null);
                    if (path.sourceItemTag() != null) {
                        buf.writeResourceLocation(path.sourceItemTag());
                    }
                    encodeConditionList(buf, path.entryConditions());
                    encodeConditionList(buf, path.inheritedConditions());
                }
                // 外部注入标记
                buf.writeBoolean(item.injected());
                encodeScenarioRefs(buf, item.scenarioProbabilities());
            }

            buf.writeVarInt(table.childProbabilities().size());
            for (ChildTableEntry child : table.childProbabilities()) {
                buf.writeResourceLocation(child.tableId());
                writeProbability(buf, child.probability());
                encodeScenarioRefs(buf, child.scenarioProbabilities());
            }
        }
        encodeStructure(buf, payload.structure());
    }

    private static SyncArchaeologyCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        int tableCount = buf.readVarInt();
        List<CatalogTableDto> catalog = new ArrayList<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            ResourceLocation tableId = buf.readResourceLocation();
            Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            String type = buf.readUtf();
            int simulationCount = buf.readVarInt();

            int childCount = buf.readVarInt();
            List<ResourceLocation> childTables = new ArrayList<>(childCount);
            for (int j = 0; j < childCount; j++) childTables.add(buf.readResourceLocation());

            int scenarioCount = buf.readVarInt();
            List<ScenarioAssumptions> scenarios = new ArrayList<>(scenarioCount);
            for (int j = 0; j < scenarioCount; j++) {
                scenarios.add(new ScenarioAssumptions(buf.readUtf(), decodeConditionList(buf)));
            }

            int itemCount = buf.readVarInt();
            List<ItemEntry> items = new ArrayList<>(itemCount);
            for (int j = 0; j < itemCount; j++) {
                ResourceLocation itemId = buf.readResourceLocation();
                Component itemName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
                Component tooltipHint = buf.readBoolean()
                        ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess())
                        : null;
                Probability probability = readProbability(buf);
                LootResultSignature signature = LootResultSignature.fromStoredKey(buf.readUtf());
                if (signature == null) {
                    signature = LootResultSignature.plain(itemId);
                }
                int pathCount = buf.readVarInt();
                List<LootAcquisitionPath> acquisitionPaths = new ArrayList<>(pathCount);
                for (int k = 0; k < pathCount; k++) {
                    ResourceLocation sourceChildTable = buf.readBoolean()
                            ? buf.readResourceLocation()
                            : null;
                    ResourceLocation sourceItemTag = buf.readBoolean()
                            ? buf.readResourceLocation()
                            : null;
                    List<LootConditionInfo> entryConditions = decodeConditionList(buf);
                    List<LootConditionInfo> inheritedConditions = decodeConditionList(buf);
                    acquisitionPaths.add(new LootAcquisitionPath(
                            sourceChildTable, sourceItemTag, entryConditions, inheritedConditions));
                }
                boolean injected = buf.readBoolean();
                items.add(new ItemEntry(itemId, itemName, tooltipHint, probability,
                        signature, acquisitionPaths, injected, decodeScenarioRefs(buf)));
            }

            int childProbabilityCount = buf.readVarInt();
            List<ChildTableEntry> childProbabilities = new ArrayList<>(childProbabilityCount);
            for (int j = 0; j < childProbabilityCount; j++) {
                childProbabilities.add(new ChildTableEntry(
                        buf.readResourceLocation(), readProbability(buf), decodeScenarioRefs(buf)));
            }

            catalog.add(new CatalogTableDto(tableId, displayName, type, simulationCount,
                    childTables, scenarios, items, childProbabilities));
        }
        return new SyncArchaeologyCatalogPayload(catalog, decodeStructure(buf));
    }

    // 分场景概率的引用形态：只写 key 与数值，条件树在表级已发过
    private static void encodeScenarioRefs(RegistryFriendlyByteBuf buf, List<ScenarioRef> refs) {
        buf.writeVarInt(refs.size());
        for (ScenarioRef ref : refs) {
            buf.writeUtf(ref.scenarioKey());
            writeProbability(buf, ref.probability());
        }
    }

    private static List<ScenarioRef> decodeScenarioRefs(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ScenarioRef> refs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            refs.add(new ScenarioRef(buf.readUtf(), readProbability(buf)));
        }
        return List.copyOf(refs);
    }

    // 概率值编码：1 字节状态 + 按需的数值，未知与不可达不占额外空间
    private static void writeProbability(RegistryFriendlyByteBuf buf, Probability probability) {
        switch (probability) {
            case Probability.Unknown ignored -> buf.writeByte(0);
            case Probability.Unreachable ignored -> buf.writeByte(1);
            case Probability.Measured measured -> {
                buf.writeByte(2);
                buf.writeDouble(measured.lower());
                buf.writeBoolean(measured.upper().isPresent());
                measured.upper().ifPresent(buf::writeDouble);
            }
        }
    }

    private static Probability readProbability(RegistryFriendlyByteBuf buf) {
        return switch (buf.readByte()) {
            case 0 -> Probability.unknown();
            case 1 -> Probability.unreachable();
            default -> {
                double lower = buf.readDouble();
                if (!buf.readBoolean()) {
                    yield Probability.measured(lower);
                }
                yield Probability.measuredRange(lower, buf.readDouble());
            }
        };
    }

    private static void encodeStructure(RegistryFriendlyByteBuf buf, CatalogStructure structure) {
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

    private static CatalogStructure decodeStructure(RegistryFriendlyByteBuf buf) {
        int categoryCount = buf.readVarInt();
        List<CatalogCategoryDefinition> categories = new ArrayList<>(categoryCount);
        for (int index = 0; index < categoryCount; index++) {
            categories.add(new CatalogCategoryDefinition(
                    buf.readResourceLocation(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readResourceLocation(), buf.readVarInt()));
        }
        int rootCount = buf.readVarInt();
        LinkedHashMap<ResourceLocation, ResourceLocation> roots = new LinkedHashMap<>();
        for (int index = 0; index < rootCount; index++) {
            roots.put(buf.readResourceLocation(), buf.readResourceLocation());
        }
        return new CatalogStructure(categories, roots);
    }

    // 递归编码单个 LootConditionInfo（含 children）
    private static void encodeConditionInfo(RegistryFriendlyByteBuf buf, LootConditionInfo info) {
        buf.writeResourceLocation(info.conditionType());
        buf.writeUtf(Component.Serializer.toJson(info.description(), buf.registryAccess()));
        buf.writeBoolean(info.probability() != null);
        if (info.probability() != null) {
            buf.writeFloat(info.probability());
        }
        buf.writeVarInt(info.children().size());
        for (LootConditionInfo child : info.children()) {
            encodeConditionInfo(buf, child);
        }
        buf.writeVarInt(info.metadata().size());
        info.metadata().forEach((key, value) -> {
            buf.writeUtf(key);
            buf.writeUtf(value);
        });
    }

    private static void encodeConditionList(RegistryFriendlyByteBuf buf, List<LootConditionInfo> conditions) {
        buf.writeVarInt(conditions.size());
        for (LootConditionInfo condition : conditions) {
            encodeConditionInfo(buf, condition);
        }
    }

    private static List<LootConditionInfo> decodeConditionList(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<LootConditionInfo> conditions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            conditions.add(decodeConditionInfo(buf));
        }
        return conditions;
    }

    // 递归解码单个 LootConditionInfo（含 children）
    private static LootConditionInfo decodeConditionInfo(RegistryFriendlyByteBuf buf) {
        ResourceLocation conditionType = buf.readResourceLocation();
        Component desc = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
        Float prob = buf.readBoolean() ? buf.readFloat() : null;
        int childCount = buf.readVarInt();
        List<LootConditionInfo> children = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            children.add(decodeConditionInfo(buf));
        }
        int metadataCount = buf.readVarInt();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 0; i < metadataCount; i++) {
            metadata.put(buf.readUtf(), buf.readUtf());
        }
        return new LootConditionInfo(conditionType, desc, prob, children, metadata);
    }
}
