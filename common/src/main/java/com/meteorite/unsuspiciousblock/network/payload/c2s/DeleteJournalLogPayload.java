package com.meteorite.unsuspiciousblock.network.payload.c2s;

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

/** 玩家日志删除请求——身份始终取服务端连接，不允许客户端指定目标玩家。 */
public record DeleteJournalLogPayload(UUID requestId,
                                      Scope scope,
                                      @Nullable ResourceLocation tableId,
                                      @Nullable UUID entryId,
                                      List<UUID> entryIds) implements CustomPacketPayload {
    public static final int MAX_BATCH_ENTRIES = 65_536;
    public static final Type<DeleteJournalLogPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "delete_journal_log"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteJournalLogPayload> STREAM_CODEC =
            StreamCodec.of(DeleteJournalLogPayload::encode, DeleteJournalLogPayload::decode);

    public DeleteJournalLogPayload {
        entryIds = entryIds == null ? List.of() : List.copyOf(entryIds);
        if (entryIds.size() > MAX_BATCH_ENTRIES) {
            throw new IllegalArgumentException("Journal batch deletion exceeds " + MAX_BATCH_ENTRIES + " entries");
        }
    }

    public static DeleteJournalLogPayload entry(ResourceLocation tableId, UUID entryId) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.ENTRY, tableId, entryId, List.of());
    }

    public static DeleteJournalLogPayload batch(ResourceLocation tableId, List<UUID> entryIds) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.BATCH, tableId, null, List.copyOf(entryIds));
    }

    public static DeleteJournalLogPayload table(ResourceLocation tableId) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.TABLE, tableId, null, List.of());
    }

    public static DeleteJournalLogPayload all() {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.ALL, null, null, List.of());
    }

    @Override
    public @NotNull Type<DeleteJournalLogPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, DeleteJournalLogPayload payload) {
        buf.writeUUID(payload.requestId);
        buf.writeByte(payload.scope.id);
        buf.writeBoolean(payload.tableId != null);
        if (payload.tableId != null) {
            buf.writeResourceLocation(payload.tableId);
        }
        buf.writeBoolean(payload.entryId != null);
        if (payload.entryId != null) {
            buf.writeUUID(payload.entryId);
        }
        buf.writeVarInt(payload.entryIds.size());
        for (UUID entryId : payload.entryIds) {
            buf.writeUUID(entryId);
        }
    }

    private static DeleteJournalLogPayload decode(RegistryFriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        Scope scope = Scope.fromId(buf.readByte());
        ResourceLocation tableId = buf.readBoolean() ? buf.readResourceLocation() : null;
        UUID entryId = buf.readBoolean() ? buf.readUUID() : null;
        int entryCount = buf.readVarInt();
        if (entryCount < 0 || entryCount > MAX_BATCH_ENTRIES) {
            throw new DecoderException("Invalid journal batch deletion size: " + entryCount);
        }
        List<UUID> entryIds = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            entryIds.add(buf.readUUID());
        }
        if (scope == Scope.ENTRY && (tableId == null || entryId == null)
                || scope == Scope.BATCH && (tableId == null || entryIds.isEmpty())
                || scope == Scope.TABLE && tableId == null) {
            throw new DecoderException("Invalid journal deletion target for scope " + scope);
        }
        return new DeleteJournalLogPayload(requestId, scope, tableId, entryId, List.copyOf(entryIds));
    }

    public enum Scope {
        ENTRY(0),
        TABLE(1),
        ALL(2),
        BATCH(3);

        private final int id;

        Scope(int id) {
            this.id = id;
        }

        private static Scope fromId(int id) {
            for (Scope scope : values()) {
                if (scope.id == id) {
                    return scope;
                }
            }
            throw new DecoderException("Unknown journal deletion scope: " + id);
        }
    }
}
