package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.Match;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Application;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Preview;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 管理会话的世界状态、个人最近记录和请求回执。 */
public record SyncLootTableManagementPayload(boolean canEditNames, ConfigView config,
        @Nullable List<RecentEntry> recentEntries, @Nullable Receipt receipt) implements CustomPacketPayload {
    public static final Type<SyncLootTableManagementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_loot_tracking_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncLootTableManagementPayload> STREAM_CODEC =
            StreamCodec.of(SyncLootTableManagementPayload::encode, SyncLootTableManagementPayload::decode);

    /** 某张表的目标规则与已生效的目录位置。 */
    public record Entry(ResourceLocation tableId, Match match, boolean isCatalogRoot,
            boolean isCollected, boolean hasExactOverride, List<ResourceLocation> parents, List<String> selectors) {}

    /** 规则草稿的基准版本与世界上实际生效的版本。 */
    public record ConfigView(long revision, long resourceRevision, long appliedRevision, long catalogGeneration,
            boolean canEdit, boolean isWritable, Application application, @Nullable String settingsJson) {}

    /** 保存或预览回执，版本指向该次操作的目标配置。 */
    public record Receipt(int requestId, EditResult result, long revision) {}

    /** 只在管理页请求时同步的个人记录。 */
    public record RecentEntry(ResourceLocation tableId, long encounterOrder) {}

    @Override
    public Type<SyncLootTableManagementPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncLootTableManagementPayload payload) {
        buf.writeBoolean(payload.canEditNames());
        ConfigView config = payload.config();
        buf.writeVarLong(config.revision());
        buf.writeVarLong(config.resourceRevision());
        buf.writeVarLong(config.appliedRevision());
        buf.writeVarLong(config.catalogGeneration());
        buf.writeBoolean(config.canEdit());
        buf.writeBoolean(config.isWritable());
        buf.writeEnum(config.application());
        buf.writeNullable(config.settingsJson(), (buffer, text) ->
                buffer.writeUtf(text, UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS));
        buf.writeNullable(payload.recentEntries(), (buffer, entries) -> buffer.writeCollection(entries, (out, entry) -> {
            out.writeResourceLocation(entry.tableId());
            out.writeVarLong(entry.encounterOrder());
        }));
        buf.writeNullable(payload.receipt(), (buffer, receipt) -> {
            buffer.writeVarInt(receipt.requestId());
            buffer.writeEnum(receipt.result());
            buffer.writeVarLong(receipt.revision());
        });
    }

    private static SyncLootTableManagementPayload decode(RegistryFriendlyByteBuf buf) {
        boolean canEditNames = buf.readBoolean();
        ConfigView config = new ConfigView(buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readEnum(Application.class),
                buf.readNullable(buffer -> buffer.readUtf(UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS)));
        List<RecentEntry> recent = buf.readNullable(buffer -> buffer.readList(in ->
                new RecentEntry(in.readResourceLocation(), in.readVarLong())));
        Receipt receipt = buf.readNullable(buffer -> new Receipt(
                buffer.readVarInt(), buffer.readEnum(EditResult.class), buffer.readVarLong()));
        return new SyncLootTableManagementPayload(canEditNames, config, recent, receipt);
    }

    /** 公共索引的实际字节格式。 */
    public static void writeEntries(FriendlyByteBuf buf, List<Entry> entries) {
        buf.writeCollection(entries, (out, entry) -> {
            out.writeResourceLocation(entry.tableId());
            out.writeEnum(entry.match());
            out.writeBoolean(entry.isCatalogRoot());
            out.writeBoolean(entry.isCollected());
            out.writeBoolean(entry.hasExactOverride());
            writeIds(out, entry.parents());
            out.writeCollection(entry.selectors(), (buffer, selector) -> buffer.writeUtf(selector));
        });
    }

    public static List<Entry> readEntries(FriendlyByteBuf buf) {
        return buf.readList(in -> new Entry(in.readResourceLocation(), in.readEnum(Match.class),
                in.readBoolean(), in.readBoolean(), in.readBoolean(), readIds(in), in.readList(FriendlyByteBuf::readUtf)));
    }

    /** 规则预览与普通状态包使用相同的版本，差异列表独立分片。 */
    public static void writePreview(FriendlyByteBuf buf, Preview preview) {
        writeIds(buf, preview.added());
        writeIds(buf, preview.removed());
        writeIds(buf, preview.changedPositions());
        buf.writeCollection(preview.unmatchedSelectors(), (out, text) -> out.writeUtf(text));
        writeIds(buf, preview.mergedVariants());
        writeIds(buf, preview.parents());
    }

    public static Preview readPreview(FriendlyByteBuf buf) {
        return new Preview(readIds(buf), readIds(buf), readIds(buf), buf.readList(FriendlyByteBuf::readUtf),
                readIds(buf), readIds(buf));
    }

    private static void writeIds(FriendlyByteBuf buf, List<ResourceLocation> ids) {
        buf.writeCollection(ids, FriendlyByteBuf::writeResourceLocation);
    }

    private static List<ResourceLocation> readIds(FriendlyByteBuf buf) {
        return buf.readList(FriendlyByteBuf::readResourceLocation);
    }
}
