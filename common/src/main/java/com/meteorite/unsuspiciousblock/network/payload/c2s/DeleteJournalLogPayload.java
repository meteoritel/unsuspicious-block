package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 玩家日志删除请求——身份始终取服务端连接，不允许客户端指定目标玩家。 */
public record DeleteJournalLogPayload(UUID requestId,
                                      Scope scope,
                                      @Nullable ResourceLocation tableId,
                                      @Nullable UUID entryId) implements CustomPacketPayload {
    public static final Type<DeleteJournalLogPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "delete_journal_log"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteJournalLogPayload> STREAM_CODEC =
            StreamCodec.of(DeleteJournalLogPayload::encode, DeleteJournalLogPayload::decode);

    public static DeleteJournalLogPayload entry(ResourceLocation tableId, UUID entryId) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.ENTRY, tableId, entryId);
    }

    public static DeleteJournalLogPayload table(ResourceLocation tableId) {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.TABLE, tableId, null);
    }

    public static DeleteJournalLogPayload all() {
        return new DeleteJournalLogPayload(UUID.randomUUID(), Scope.ALL, null, null);
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
    }

    private static DeleteJournalLogPayload decode(RegistryFriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        Scope scope = Scope.fromId(buf.readByte());
        ResourceLocation tableId = buf.readBoolean() ? buf.readResourceLocation() : null;
        UUID entryId = buf.readBoolean() ? buf.readUUID() : null;
        if (scope == Scope.ENTRY && (tableId == null || entryId == null)
                || scope == Scope.TABLE && tableId == null) {
            throw new DecoderException("Invalid journal deletion target for scope " + scope);
        }
        return new DeleteJournalLogPayload(requestId, scope, tableId, entryId);
    }

    public enum Scope {
        ENTRY(0),
        TABLE(1),
        ALL(2);

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
