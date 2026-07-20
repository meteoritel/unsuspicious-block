package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
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
public record SyncArchaeologyCatalogPayload(Map<ResourceLocation, TableDefinition> catalog)
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
                // 子表来源（null=根表直接产出）
                buf.writeBoolean(item.sourceChildTable() != null);
                if (item.sourceChildTable() != null) {
                    buf.writeResourceLocation(item.sourceChildTable());
                }
                // conditions（含树形 children，递归编码）
                buf.writeVarInt(item.conditions().size());
                for (LootConditionInfo info : item.conditions()) {
                    encodeConditionInfo(buf, info);
                }
                // parentTableConditions（子表条目条件，递归编码）
                buf.writeVarInt(item.parentTableConditions().size());
                for (LootConditionInfo info : item.parentTableConditions()) {
                    encodeConditionInfo(buf, info);
                }
                // 外部注入标记
                buf.writeBoolean(item.injected());
            }
            buf.writeVarInt(table.simulationCount());
        }
    }

    private static SyncArchaeologyCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        int tableCount = buf.readVarInt();
        LinkedHashMap<ResourceLocation, TableDefinition> catalog = new LinkedHashMap<>();
        for (int i = 0; i < tableCount; i++) {
            ResourceLocation tableId = buf.readResourceLocation();
            Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            String type = buf.readUtf();
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
                // 子表来源（null=根表直接产出）
                ResourceLocation sourceChildTable = buf.readBoolean()
                        ? buf.readResourceLocation()
                        : null;
                // conditions（含树形 children，递归解码）
                int conditionCount = buf.readVarInt();
                List<LootConditionInfo> conditions = new ArrayList<>(conditionCount);
                for (int k = 0; k < conditionCount; k++) {
                    conditions.add(decodeConditionInfo(buf));
                }
                // parentTableConditions（子表条目条件，递归解码）
                int parentConditionCount = buf.readVarInt();
                List<LootConditionInfo> parentTableConditions = new ArrayList<>(parentConditionCount);
                for (int k = 0; k < parentConditionCount; k++) {
                    parentTableConditions.add(decodeConditionInfo(buf));
                }
                boolean injected = buf.readBoolean();
                items.add(new ItemDefinition(itemId, itemName, tooltipHint, probability, signature, sourceChildTable, conditions, parentTableConditions, injected));
            }
            int simulationCount = buf.readVarInt();
            catalog.put(tableId, new TableDefinition(tableId, displayName, type, items, simulationCount));
        }
        return new SyncArchaeologyCatalogPayload(catalog);
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
