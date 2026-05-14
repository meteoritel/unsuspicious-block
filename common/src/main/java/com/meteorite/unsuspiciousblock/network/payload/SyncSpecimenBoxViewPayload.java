package com.meteorite.unsuspiciousblock.network.payload;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 标本箱菜单视图同步包——服务端→客户端。 */
public record SyncSpecimenBoxViewPayload(int containerId,
                                         List<TableEntry> tables,
                                         int selectedTableIndex,
                                         int pageIndex,
                                         int pageCount,
                                         List<LogicalSlotEntry> logicalSlots) implements CustomPacketPayload {
    public static final Type<SyncSpecimenBoxViewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_specimen_box_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSpecimenBoxViewPayload> STREAM_CODEC =
            StreamCodec.of(SyncSpecimenBoxViewPayload::encode, SyncSpecimenBoxViewPayload::decode);

    @Override
    public @NotNull Type<SyncSpecimenBoxViewPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncSpecimenBoxViewPayload payload) {
        buf.writeVarInt(payload.containerId);
        buf.writeVarInt(payload.tables.size());
        for (TableEntry table : payload.tables) {
            buf.writeResourceLocation(table.tableId());
            buf.writeUtf(Component.Serializer.toJson(table.displayName(), buf.registryAccess()));
        }
        buf.writeVarInt(payload.selectedTableIndex);
        buf.writeVarInt(payload.pageIndex);
        buf.writeVarInt(payload.pageCount);
        buf.writeVarInt(payload.logicalSlots.size());
        for (LogicalSlotEntry slot : payload.logicalSlots) {
            buf.writeUtf(Component.Serializer.toJson(slot.displayName(), buf.registryAccess()));
            buf.writeUtf(slot.signatureKey());
            buf.writeBoolean(slot.unlocked());
            buf.writeVarInt(slot.journalCount());
            buf.writeVarInt(slot.storedCount());
        }
    }

    private static SyncSpecimenBoxViewPayload decode(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int tableCount = buf.readVarInt();
        List<TableEntry> tables = new ArrayList<>(tableCount);
        for (int index = 0; index < tableCount; index++) {
            ResourceLocation tableId = buf.readResourceLocation();
            Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            tables.add(new TableEntry(tableId, displayName));
        }
        int selectedTableIndex = buf.readVarInt();
        int pageIndex = buf.readVarInt();
        int pageCount = buf.readVarInt();
        int logicalSlotCount = buf.readVarInt();
        List<LogicalSlotEntry> logicalSlots = new ArrayList<>(logicalSlotCount);
        for (int index = 0; index < logicalSlotCount; index++) {
            Component displayName = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
            String signatureKey = buf.readUtf();
            boolean unlocked = buf.readBoolean();
            int journalCount = buf.readVarInt();
            int storedCount = buf.readVarInt();
            logicalSlots.add(new LogicalSlotEntry(displayName, signatureKey, unlocked, journalCount, storedCount));
        }
        return new SyncSpecimenBoxViewPayload(containerId, tables, selectedTableIndex, pageIndex, pageCount, logicalSlots);
    }

    /** 左侧目录里的单个表项。 */
    public record TableEntry(ResourceLocation tableId, Component displayName) {
    }

    /** 右侧当前页里的单个逻辑槽位。 */
    public record LogicalSlotEntry(Component displayName, String signatureKey, boolean unlocked,
                                   int journalCount, int storedCount) {
    }
}
