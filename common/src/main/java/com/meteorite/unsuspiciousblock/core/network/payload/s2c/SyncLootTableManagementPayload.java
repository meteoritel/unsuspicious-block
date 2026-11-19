package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.Match;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 服务端权威战利品表管理索引及名称覆盖快照。 */
public record SyncLootTableManagementPayload(List<Entry> entries, List<RecentEntry> recentEntries, boolean canEditNames,
                                             Map<String, Map<String, String>> translations, ConfigView config,
                                             @Nullable Receipt receipt)
        implements CustomPacketPayload {
    public static final Type<SyncLootTableManagementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_loot_tracking_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncLootTableManagementPayload> STREAM_CODEC =
            StreamCodec.of(SyncLootTableManagementPayload::encode, SyncLootTableManagementPayload::decode);

    /** 某张表的规则来源和当前目录代次中的呈现。 */
    public record Entry(ResourceLocation tableId, Match match, boolean isCatalogRoot,
                        boolean isCollected, boolean hasExactOverride) {}

    /** 世界规则页的可编辑草稿及实际可用状态。 */
    public record ConfigView(long revision, boolean canEdit, boolean isWritable, boolean isCatalogPending,
                             @Nullable String settingsJson) {}

    /** 将一次保存结果关联到客户端发出的请求。 */
    public record Receipt(int requestId, EditResult result) {}

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
            buf.writeEnum(entry.match);
            buf.writeBoolean(entry.isCatalogRoot);
            buf.writeBoolean(entry.isCollected);
            buf.writeBoolean(entry.hasExactOverride);
        });
        buf.writeVarInt(payload.recentEntries.size());
        payload.recentEntries.forEach(entry -> {
            buf.writeResourceLocation(entry.tableId);
            buf.writeLong(entry.encounterOrder);
        });
        buf.writeBoolean(payload.canEditNames);
        buf.writeVarInt(payload.translations.size());
        payload.translations.forEach((language, values) -> {
            buf.writeUtf(language, 16);
            buf.writeVarInt(values.size());
            values.forEach((key, value) -> {
                buf.writeUtf(key, 256);
                buf.writeUtf(value, 128);
            });
        });
        ConfigView config = payload.config;
        buf.writeVarLong(config.revision());
        buf.writeBoolean(config.canEdit());
        buf.writeBoolean(config.isWritable());
        buf.writeBoolean(config.isCatalogPending());
        buf.writeBoolean(config.settingsJson() != null);
        if (config.settingsJson() != null) {
            buf.writeUtf(config.settingsJson(), UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS);
        }
        buf.writeBoolean(payload.receipt != null);
        if (payload.receipt != null) {
            buf.writeVarInt(payload.receipt.requestId());
            buf.writeEnum(payload.receipt.result());
        }
    }

    private static SyncLootTableManagementPayload decode(RegistryFriendlyByteBuf buf) {
        int entryCount = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            entries.add(new Entry(buf.readResourceLocation(), buf.readEnum(Match.class),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        }
        int recentCount = buf.readVarInt();
        List<RecentEntry> recentEntries = new ArrayList<>(recentCount);
        for (int index = 0; index < recentCount; index++) {
            recentEntries.add(new RecentEntry(buf.readResourceLocation(), buf.readLong()));
        }
        boolean canEditNames = buf.readBoolean();
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
        ConfigView config = new ConfigView(buf.readVarLong(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean() ? buf.readUtf(UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS) : null);
        Receipt receipt = buf.readBoolean() ? new Receipt(buf.readVarInt(), buf.readEnum(EditResult.class)) : null;
        return new SyncLootTableManagementPayload(
                List.copyOf(entries), List.copyOf(recentEntries), canEditNames, Map.copyOf(translations), config, receipt);
    }
}
