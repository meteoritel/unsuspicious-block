package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 日志备注更新包（客户端→服务端）。
 * 玩家在日志详情页编辑备注后发送，服务端据此更新对应条目的 note 字段并同步给其他客户端。
 */
public record UpdateJournalLogNotePayload(ResourceLocation tableId,
                                          UUID entryId,
                                          String note) implements CustomPacketPayload {

    public static final Type<UpdateJournalLogNotePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_journal_log_note"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateJournalLogNotePayload> STREAM_CODEC =
            StreamCodec.of(
                    UpdateJournalLogNotePayload::encode,
                    UpdateJournalLogNotePayload::decode
            );

    @Override
    public @NotNull Type<UpdateJournalLogNotePayload> type() {
        return TYPE;
    }

    // 编码：依次写入表 ID、条目 ID、备注字符串（可能为空）
    private static void encode(RegistryFriendlyByteBuf buf, UpdateJournalLogNotePayload payload) {
        buf.writeResourceLocation(payload.tableId);
        buf.writeUUID(payload.entryId);
        buf.writeUtf(payload.note, 512);
    }

    // 解码：按写入顺序读取
    private static UpdateJournalLogNotePayload decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation tableId = buf.readResourceLocation();
        UUID entryId = buf.readUUID();
        String note = buf.readUtf(512);
        return new UpdateJournalLogNotePayload(tableId, entryId, note);
    }
}
