package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
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

/** 全量目录同步包 —— 服务端→客户端 */
public record SyncArchaeologyCatalogPayload(Map<ResourceLocation, TableDefinition> catalog,
                                            CatalogStructure structure)
        implements CustomPacketPayload {

    public static final Type<SyncArchaeologyCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_archaeology_catalog"));

    @Override
    public @NotNull Type<SyncArchaeologyCatalogPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncArchaeologyCatalogPayload> STREAM_CODEC =
            StreamCodec.of(SyncArchaeologyCatalogPayload::encode, SyncArchaeologyCatalogPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SyncArchaeologyCatalogPayload payload) {
        buf.writeVarInt(payload.catalog.size());
        for (Map.Entry<ResourceLocation, TableDefinition> entry : payload.catalog.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            TableDefinition table = entry.getValue();
            buf.writeUtf(Component.Serializer.toJson(table.displayName(), buf.registryAccess()));
            buf.writeUtf(table.type());
            buf.writeVarInt(table.childTables().size());
            table.childTables().forEach(buf::writeResourceLocation);
            buf.writeVarInt(table.items().size());
            for (ItemDefinition item : table.items()) {
                buf.writeResourceLocation(item.id());
                buf.writeUtf(Component.Serializer.toJson(item.displayName(), buf.registryAccess()));
                buf.writeBoolean(item.tooltipHint() != null);
                if (item.tooltipHint() != null) {
                    buf.writeUtf(Component.Serializer.toJson(item.tooltipHint(), buf.registryAccess()));
                }
                buf.writeUtf(item.probability());
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
            }
            buf.writeVarInt(table.simulationCount());
        }
        encodeStructure(buf, payload.structure());
    }

    private static SyncArchaeologyCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        int tableCount = buf.readVarInt();
        LinkedHashMap<ResourceLocation, TableDefinition> catalog = new LinkedHashMap<>();
        for (int i = 0; i < tableCount; i++) {
            ResourceLocation tableId = buf.readResourceLocation();
            Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            String type = buf.readUtf();
            int childCount = buf.readVarInt();
            List<ResourceLocation> childTables = new ArrayList<>(childCount);
            for (int j = 0; j < childCount; j++) childTables.add(buf.readResourceLocation());
            int itemCount = buf.readVarInt();
            List<ItemDefinition> items = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                ResourceLocation itemId = buf.readResourceLocation();
                Component itemName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
                Component tooltipHint = buf.readBoolean()
                        ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess())
                        : null;
                String probability = buf.readUtf();
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
                items.add(new ItemDefinition(itemId, itemName, tooltipHint, probability,
                        signature, acquisitionPaths, injected));
            }
            int simulationCount = buf.readVarInt();
            catalog.put(tableId, new TableDefinition(tableId, displayName, type, items,
                    simulationCount, childTables));
        }
        return new SyncArchaeologyCatalogPayload(catalog, decodeStructure(buf));
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
        return new LootConditionInfo(conditionType, desc, prob, children);
    }
}
