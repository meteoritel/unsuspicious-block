package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 按完整请求受理删除，批量请求通过固定长度 UUID 分片传输。 */
public record DeleteJournalLogPayload(UUID requestId, Scope scope, @Nullable ResourceLocation tableId,
                                      int total, int offset, List<UUID> entryIds) implements CustomPacketPayload {
    public static final int MAX_BATCH_ENTRIES = 65_536;
    private static final int MAX_BYTES = 30 * 1024;
    private static final int HEADER_RESERVE = 96;
    private static final int UUID_BYTES = 16;
    public static final Type<DeleteJournalLogPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "delete_journal_log_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteJournalLogPayload> STREAM_CODEC =
            StreamCodec.of(DeleteJournalLogPayload::encode, DeleteJournalLogPayload::decode);

    public static DeleteJournalLogPayload entry(ResourceLocation tableId, UUID entryId) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.ENTRY, tableId, 1, 0, List.of(entryId));
    }

    /** 每片使用相同身份、范围和总条目数。 */
    public static List<DeleteJournalLogPayload> batch(ResourceLocation tableId, List<UUID> entryIds) {
        int capacity = capacity(tableId);
        if (capacity < 1 || entryIds.isEmpty() || entryIds.size() > MAX_BATCH_ENTRIES) {
            throw new IllegalArgumentException("考古日志批量删除超过线路容量");
        }
        UUID requestId = UUID.randomUUID();
        List<DeleteJournalLogPayload> parts = new ArrayList<>();
        for (int offset = 0; offset < entryIds.size(); offset += capacity) {
            parts.add(new DeleteJournalLogPayload(requestId, Scope.BATCH, tableId, entryIds.size(),
                    offset, List.copyOf(entryIds.subList(offset, Math.min(offset + capacity, entryIds.size())))));
        }
        return List.copyOf(parts);
    }

    public static DeleteJournalLogPayload table(ResourceLocation tableId) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.TABLE, tableId, 0, 0, List.of());
    }

    public static DeleteJournalLogPayload all() {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.ALL, null, 0, 0, List.of());
    }

    @Override
    public @NotNull Type<DeleteJournalLogPayload> type() { return TYPE; }

    private static int capacity(ResourceLocation tableId) {
        // ResourceLocation 的合法字符均为 ASCII。
        return (MAX_BYTES - HEADER_RESERVE - tableId.toString().length()) / UUID_BYTES;
    }

    private static void encode(RegistryFriendlyByteBuf buf, DeleteJournalLogPayload payload) {
        buf.writeUUID(payload.requestId);
        buf.writeEnum(payload.scope);
        if (payload.tableId != null) buf.writeResourceLocation(payload.tableId);
        buf.writeVarInt(payload.total);
        buf.writeVarInt(payload.offset);
        payload.entryIds.forEach(buf::writeUUID);
    }

    private static DeleteJournalLogPayload decode(RegistryFriendlyByteBuf buf) {
        if (buf.readableBytes() > MAX_BYTES) throw new DecoderException("考古日志删除分片超过容量");
        UUID requestId = buf.readUUID();
        Scope scope = buf.readEnum(Scope.class);
        ResourceLocation tableId = scope == Scope.ALL ? null : buf.readResourceLocation();
        int total = buf.readVarInt();
        int offset = buf.readVarInt();
        int count = buf.readableBytes() / UUID_BYTES;
        boolean valid = switch (scope) {
            case ENTRY -> offset == 0 && total == 1 && count == 1;
            case TABLE, ALL -> offset == 0 && total == 0 && count == 0;
            case BATCH -> {
                int capacity = capacity(tableId);
                yield capacity > 0 && offset >= 0 && offset < total && total <= MAX_BATCH_ENTRIES
                        && offset % capacity == 0 && count == Math.min(capacity, total - offset);
            }
        };
        if (!valid || buf.readableBytes() % UUID_BYTES != 0) {
            throw new DecoderException("考古日志删除范围或分片不一致");
        }
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        return new DeleteJournalLogPayload(requestId, scope, tableId, total, offset, List.copyOf(ids));
    }

    /** 玩家确认的删除范围。 */
    public enum Scope { ENTRY, TABLE, ALL, BATCH }
}
