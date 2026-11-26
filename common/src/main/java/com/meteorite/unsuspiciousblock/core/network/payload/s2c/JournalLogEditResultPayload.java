package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 与原始编辑请求及已接受日志修订关联的回执。 */
public record JournalLogEditResultPayload(UUID requestId, Operation operation, @Nullable ResourceLocation tableId,
                                          Result result, long revision, int removedCount,
                                          int retention, int globalLimit) implements CustomPacketPayload {
    public static final Type<JournalLogEditResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "journal_log_edit_result_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalLogEditResultPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeUUID(payload.requestId());
                buf.writeEnum(payload.operation());
                buf.writeBoolean(payload.tableId() != null);
                if (payload.tableId() != null) buf.writeResourceLocation(payload.tableId());
                buf.writeEnum(payload.result());
                buf.writeVarLong(payload.revision());
                buf.writeVarInt(payload.removedCount());
                buf.writeVarInt(payload.retention());
                buf.writeVarInt(payload.globalLimit());
            }, buf -> {
                UUID requestId = buf.readUUID();
                Operation operation = buf.readEnum(Operation.class);
                ResourceLocation table = buf.readBoolean() ? buf.readResourceLocation() : null;
                Result result = buf.readEnum(Result.class);
                long revision = buf.readVarLong();
                int removed = buf.readVarInt();
                int retention = buf.readVarInt();
                int limit = buf.readVarInt();
                if (revision < 0 || removed < 0 || retention < 0 || limit < 1
                        || (result == Result.APPLIED) != (revision > 0) || buf.isReadable()) {
                    throw new DecoderException("考古日志编辑回执无效");
                }
                return new JournalLogEditResultPayload(requestId, operation, table, result, revision, removed, retention, limit);
            });

    /** 对应独立编辑动作。 */
    public enum Operation { NOTE, RETENTION, PRUNE, DELETE }

    /** 用于草稿和确认页反馈的受理结果。 */
    public enum Result { APPLIED, UNCHANGED, NOT_FOUND, INVALID, SAVE_FAILED, EXPIRED }

    @Override
    public @NotNull Type<JournalLogEditResultPayload> type() { return TYPE; }
}
