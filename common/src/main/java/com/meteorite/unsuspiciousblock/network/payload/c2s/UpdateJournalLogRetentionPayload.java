package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 玩家修改当前战利品表日志保留策略的请求。 */
public record UpdateJournalLogRetentionPayload(ResourceLocation tableId,
                                               Action action,
                                               int value) implements CustomPacketPayload {
    public static final Type<UpdateJournalLogRetentionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_journal_log_retention"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateJournalLogRetentionPayload> STREAM_CODEC =
            StreamCodec.of(UpdateJournalLogRetentionPayload::encode, UpdateJournalLogRetentionPayload::decode);

    @Override
    public @NotNull Type<UpdateJournalLogRetentionPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, UpdateJournalLogRetentionPayload payload) {
        buf.writeResourceLocation(payload.tableId);
        buf.writeByte(payload.action.id);
        buf.writeVarInt(payload.value);
    }

    private static UpdateJournalLogRetentionPayload decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation tableId = buf.readResourceLocation();
        Action action = Action.fromId(buf.readUnsignedByte());
        return new UpdateJournalLogRetentionPayload(tableId, action, buf.readVarInt());
    }

    public enum Action {
        SET_LIMIT(0),
        PRUNE_KEEP_RECENT(1);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        private static Action fromId(int id) {
            for (Action action : values()) {
                if (action.id == id) {
                    return action;
                }
            }
            throw new DecoderException("Unknown journal retention action: " + id);
        }
    }
}
