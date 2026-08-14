package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 服务端权威战利品表管理索引及名称覆盖快照。 */
public record SyncLootTableManagementPayload(List<Entry> entries, List<RecentEntry> recentEntries, boolean canEdit,
                                             Map<String, Map<String, String>> translations)
        implements CustomPacketPayload {
    public static final Type<SyncLootTableManagementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_loot_table_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncLootTableManagementPayload> STREAM_CODEC =
            StreamCodec.of(SyncLootTableManagementPayload::encode, SyncLootTableManagementPayload::decode);

    public record Entry(ResourceLocation tableId, boolean tracked) { }

    /** 最近遇到的战利品表及其玩家内顺序。 */
    public record RecentEntry(ResourceLocation tableId, long encounterOrder) { }

    @Override
    public @NotNull Type<SyncLootTableManagementPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncLootTableManagementPayload payload) {
        buf.writeVarInt(payload.entries.size());
        payload.entries.forEach(entry -> {
            buf.writeResourceLocation(entry.tableId);
            buf.writeBoolean(entry.tracked);
        });
        buf.writeVarInt(payload.recentEntries.size());
        payload.recentEntries.forEach(entry -> {
            buf.writeResourceLocation(entry.tableId);
            buf.writeLong(entry.encounterOrder);
        });
        buf.writeBoolean(payload.canEdit);
        buf.writeVarInt(payload.translations.size());
        payload.translations.forEach((language, values) -> {
            buf.writeUtf(language, 16);
            buf.writeVarInt(values.size());
            values.forEach((key, value) -> {
                buf.writeUtf(key, 256);
                buf.writeUtf(value, 128);
            });
        });
    }

    private static SyncLootTableManagementPayload decode(RegistryFriendlyByteBuf buf) {
        int entryCount = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            entries.add(new Entry(buf.readResourceLocation(), buf.readBoolean()));
        }
        int recentCount = buf.readVarInt();
        List<RecentEntry> recentEntries = new ArrayList<>(recentCount);
        for (int index = 0; index < recentCount; index++) {
            recentEntries.add(new RecentEntry(buf.readResourceLocation(), buf.readLong()));
        }
        boolean canEdit = buf.readBoolean();
        int languageCount = buf.readVarInt();
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        for (int index = 0; index < languageCount; index++) {
            String language = buf.readUtf(16);
            int valueCount = buf.readVarInt();
            Map<String, String> values = new LinkedHashMap<>();
            for (int valueIndex = 0; valueIndex < valueCount; valueIndex++) {
                values.put(buf.readUtf(256), buf.readUtf(128));
            }
            translations.put(language, values);
        }
        return new SyncLootTableManagementPayload(
                List.copyOf(entries), List.copyOf(recentEntries), canEdit, Map.copyOf(translations));
    }
}
