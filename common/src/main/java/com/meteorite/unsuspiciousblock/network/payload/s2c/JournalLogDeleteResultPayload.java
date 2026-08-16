package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 日志删除结果回执——用于客户端反馈成功、目标不存在或请求无效。 */
public record JournalLogDeleteResultPayload(UUID requestId,
                                            Result result,
                                            int removedCount) implements CustomPacketPayload {
    public static final Type<JournalLogDeleteResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "journal_log_delete_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalLogDeleteResultPayload> STREAM_CODEC =
            StreamCodec.of(JournalLogDeleteResultPayload::encode, JournalLogDeleteResultPayload::decode);

    @Override
    public @NotNull Type<JournalLogDeleteResultPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, JournalLogDeleteResultPayload payload) {
        buf.writeUUID(payload.requestId);
        buf.writeByte(payload.result.ordinal());
        buf.writeVarInt(Math.max(0, payload.removedCount));
    }

    private static JournalLogDeleteResultPayload decode(RegistryFriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        int resultId = buf.readUnsignedByte();
        Result[] results = Result.values();
        Result result = resultId < results.length ? results[resultId] : Result.INVALID;
        return new JournalLogDeleteResultPayload(requestId, result, Math.max(0, buf.readVarInt()));
    }

    public enum Result {
        SUCCESS,
        NOT_FOUND,
        INVALID
    }
}
