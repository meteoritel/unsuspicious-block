package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 有界分片信封，完整事务的含义由各数据流定义。 */
public record JournalTransferPayload(Kind kind, long revision, long ticket,
                                     int total, int offset, byte[] bytes) implements CustomPacketPayload {
    public static final int MAX_PAYLOAD_BYTES = 128 * 1024;
    public static final int HEADER_BYTES = 96;
    public static final int PIECE_BYTES = MAX_PAYLOAD_BYTES - HEADER_BYTES;
    public static final Type<JournalTransferPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "journal_transfer_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalTransferPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.kind());
                buffer.writeVarLong(payload.revision());
                buffer.writeVarLong(payload.ticket());
                buffer.writeVarInt(payload.total());
                buffer.writeVarInt(payload.offset());
                buffer.writeByteArray(payload.bytes());
            }, buffer -> {
                Kind kind = buffer.readEnum(Kind.class);
                long revision = buffer.readVarLong();
                long ticket = buffer.readVarLong();
                int total = buffer.readVarInt();
                int offset = buffer.readVarInt();
                byte[] bytes = buffer.readByteArray(PIECE_BYTES);
                boolean failure = total == 0 && offset == 0 && bytes.length == 0;
                boolean validPiece = offset >= 0 && offset < total && offset % PIECE_BYTES == 0
                        && bytes.length == Math.min(PIECE_BYTES, total - offset);
                if (revision < 0 || ticket < 0 || total > kind.maxBytes() || (!failure && !validPiece)
                        || buffer.isReadable()) {
                    throw new DecoderException("考古笔记分片信封无效");
                }
                return new JournalTransferPayload(kind, revision, ticket, total, offset, bytes);
            });

    /** 同一流按发送顺序一次提交一个事务。 */
    public enum Stream { LOG, PROGRESS, CATALOG }

    /** 总预算与解码端共享。历史预算覆盖服务端分片存档的 NBT 读取配额。 */
    public enum Kind {
        LOG_SUMMARY(Stream.LOG, 64),
        LOG_CHANGE(Stream.LOG, 64),
        LOG_HISTORY(Stream.LOG, 64),
        PROGRESS_BASELINE(Stream.PROGRESS, 64),
        PROGRESS_CHANGE(Stream.PROGRESS, 64),
        CATALOG(Stream.CATALOG, 64);

        private final Stream stream;
        private final int maxBytes;

        Kind(Stream stream, int maxMiB) {
            this.stream = stream;
            this.maxBytes = maxMiB * 1024 * 1024;
        }

        public Stream stream() { return stream; }
        public int maxBytes() { return maxBytes; }
    }

    @Override
    public @NotNull Type<JournalTransferPayload> type() { return TYPE; }
}
