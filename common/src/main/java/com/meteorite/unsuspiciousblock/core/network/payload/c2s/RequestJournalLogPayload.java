package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 建立摘要基线、查看单表或结束单表查询。 */
public record RequestJournalLogPayload(Action action, long ticket, @Nullable ResourceLocation tableId)
        implements CustomPacketPayload {
    public static final Type<RequestJournalLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_journal_log_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestJournalLogPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.action());
                buffer.writeVarLong(payload.ticket());
                if (payload.tableId() != null) buffer.writeResourceLocation(payload.tableId());
            }, buffer -> {
                Action action = buffer.readEnum(Action.class);
                long ticket = buffer.readVarLong();
                ResourceLocation table = action == Action.HISTORY ? buffer.readResourceLocation() : null;
                if (ticket < 0 || buffer.isReadable()) throw new DecoderException("考古日志查询无效");
                return new RequestJournalLogPayload(action, ticket, table);
            });

    /** 每张正在查看的表对应一个连接内票据。 */
    public enum Action { SUMMARY, HISTORY, CLOSE }

    @Override
    public @NotNull Type<RequestJournalLogPayload> type() { return TYPE; }
}
