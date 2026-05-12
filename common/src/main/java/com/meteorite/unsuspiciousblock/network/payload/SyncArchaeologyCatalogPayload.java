package com.meteorite.unsuspiciousblock.network.payload;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
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
            buf.writeVarInt(table.items().size());
            for (ItemDefinition item : table.items()) {
                buf.writeResourceLocation(item.id());
                buf.writeUtf(Component.Serializer.toJson(item.displayName(), buf.registryAccess()));
                buf.writeBoolean(item.tooltipHint() != null);
                if (item.tooltipHint() != null) {
                    buf.writeUtf(Component.Serializer.toJson(item.tooltipHint(), buf.registryAccess()));
                }
                buf.writeDouble(item.weight());
                buf.writeUtf(item.signature().toStoredKey());
            }
            buf.writeDouble(table.totalWeight());
            buf.writeBoolean(table.approximate());
        }
    }

    private static SyncArchaeologyCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        int tableCount = buf.readVarInt();
        LinkedHashMap<ResourceLocation, TableDefinition> catalog = new LinkedHashMap<>();
        for (int i = 0; i < tableCount; i++) {
            ResourceLocation tableId = buf.readResourceLocation();
            Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            int itemCount = buf.readVarInt();
            List<ItemDefinition> items = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                ResourceLocation itemId = buf.readResourceLocation();
                Component itemName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
                Component tooltipHint = buf.readBoolean()
                        ? Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess())
                        : null;
                double weight = buf.readDouble();
                LootResultSignature signature = LootResultSignature.fromStoredKey(buf.readUtf());
                if (signature == null) {
                    signature = LootResultSignature.plain(itemId);
                }
                items.add(new ItemDefinition(itemId, itemName, tooltipHint, weight, signature));
            }
            double totalWeight = buf.readDouble();
            boolean approximate = buf.readBoolean();
            catalog.put(tableId, new TableDefinition(tableId, displayName, items, totalWeight, approximate));
        }
        return new SyncArchaeologyCatalogPayload(catalog);
    }
}
