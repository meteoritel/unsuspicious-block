package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Result;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Status;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 战利品表管理的大载荷按实际编码字节分片，同类快照以版本和片号提交。 */
public record SyncLootManagementDataPayload(Kind kind, long revision, int sequence, boolean last, byte[] bytes)
        implements CustomPacketPayload {
    private static final int CHUNK_BYTES = 128 * 1024;
    public static final Type<SyncLootManagementDataPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_loot_management_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncLootManagementDataPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeEnum(payload.kind());
                buf.writeVarLong(payload.revision());
                buf.writeVarInt(payload.sequence());
                buf.writeBoolean(payload.last());
                buf.writeByteArray(payload.bytes());
            }, buf -> new SyncLootManagementDataPayload(buf.readEnum(Kind.class),
                    buf.readVarLong(), buf.readVarInt(), buf.readBoolean(), buf.readByteArray(CHUNK_BYTES)));

    /** 每类数据独立刷新。预览的版本是发起请求的编号。 */
    public enum Kind { INDEX, NAMES, PREVIEW, NAME_RESULT }

    @Override
    public Type<SyncLootManagementDataPayload> type() {
        return TYPE;
    }

    /** 名称回执包含供逐项恢复使用的服务端实际值。 */
    public static void writeNameResults(FriendlyByteBuf buf, List<Result> results) {
        buf.writeCollection(results, (out, result) -> {
            out.writeResourceLocation(result.tableId());
            out.writeUtf(result.language());
            out.writeEnum(result.status());
            out.writeUtf(result.current());
        });
    }

    public static List<Result> readNameResults(FriendlyByteBuf buf) {
        return buf.readList(in -> new Result(in.readResourceLocation(), in.readUtf(), in.readEnum(Status.class),
                in.readUtf()));
    }

    /** 只编码一次，缓存的分片可重复发送给需要该版本的玩家。 */
    public static List<SyncLootManagementDataPayload> encode(Kind kind, long revision,
                                                            Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            List<SyncLootManagementDataPayload> chunks = new ArrayList<>();
            do {
                byte[] bytes = new byte[Math.min(CHUNK_BYTES, buffer.readableBytes())];
                buffer.readBytes(bytes);
                chunks.add(new SyncLootManagementDataPayload(kind, revision, chunks.size(), !buffer.isReadable(), bytes));
            } while (buffer.isReadable());
            return List.copyOf(chunks);
        } finally {
            buffer.release();
        }
    }
}
