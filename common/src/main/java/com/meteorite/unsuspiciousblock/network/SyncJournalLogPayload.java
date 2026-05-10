package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record SyncJournalLogPayload(UUID sessionId,
                                    long sequence,
                                    Action action,
                                    @Nullable ResourceLocation tableId,
                                    boolean hasFirstUnlockedTime,
                                    long firstUnlockedGameTime,
                                    long firstUnlockedDayTime,
                                    boolean hasEntry,
                                    @Nullable ResourceLocation itemId,
                                    @Nullable ResourceLocation structureId,
                                    ResourceLocation biomeId,
                                    BlockPos pos,
                                    long gameTime,
                                    long dayTime) implements CustomPacketPayload {

    public static final Type<SyncJournalLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogPayload> STREAM_CODEC =
            StreamCodec.of(SyncJournalLogPayload::encode, SyncJournalLogPayload::decode);

    public static SyncJournalLogPayload firstUnlock(UUID sessionId, long sequence, ResourceLocation tableId,
                                                    long firstUnlockedGameTime, long firstUnlockedDayTime) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.APPEND, tableId, true,
                firstUnlockedGameTime, firstUnlockedDayTime, false,
                null, null, ResourceLocation.withDefaultNamespace("plains"), BlockPos.ZERO, 0L, 0L);
    }

    public static SyncJournalLogPayload excavation(UUID sessionId, long sequence, ResourceLocation tableId,
                                                   ResourceLocation itemId, @Nullable ResourceLocation structureId,
                                                   ResourceLocation biomeId, BlockPos pos, long gameTime, long dayTime) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.APPEND, tableId, false, 0L, 0L, true,
                itemId, structureId, biomeId, pos, gameTime, dayTime);
    }

    public static SyncJournalLogPayload clearAll(UUID sessionId, long sequence) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.CLEAR_ALL, null, false, 0L, 0L, false,
                null, null, ResourceLocation.withDefaultNamespace("plains"), BlockPos.ZERO, 0L, 0L);
    }

    public static SyncJournalLogPayload clearTable(UUID sessionId, long sequence, ResourceLocation tableId) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.CLEAR_TABLE, tableId, false, 0L, 0L, false,
                null, null, ResourceLocation.withDefaultNamespace("plains"), BlockPos.ZERO, 0L, 0L);
    }

    @Override
    public @NotNull Type<SyncJournalLogPayload> type() {
        return TYPE;
    }

    public enum Action {
        APPEND(0),
        CLEAR_ALL(1),
        CLEAR_TABLE(2);

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
            return APPEND;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncJournalLogPayload payload) {
        buf.writeUUID(payload.sessionId);
        buf.writeVarLong(payload.sequence);
        buf.writeByte(payload.action.id);
        if (payload.tableId != null) {
            buf.writeBoolean(true);
            buf.writeResourceLocation(payload.tableId);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeBoolean(payload.hasFirstUnlockedTime);
        if (payload.hasFirstUnlockedTime) {
            buf.writeVarLong(payload.firstUnlockedGameTime);
            buf.writeVarLong(payload.firstUnlockedDayTime);
        }
        buf.writeBoolean(payload.hasEntry);
        if (payload.hasEntry) {
            buf.writeBoolean(payload.itemId != null);
            if (payload.itemId != null) {
                buf.writeResourceLocation(payload.itemId);
            }
            buf.writeBoolean(payload.structureId != null);
            if (payload.structureId != null) {
                buf.writeResourceLocation(payload.structureId);
            }
            buf.writeResourceLocation(payload.biomeId);
            buf.writeBlockPos(payload.pos);
            buf.writeVarLong(payload.gameTime);
            buf.writeVarLong(payload.dayTime);
        }
    }

    private static SyncJournalLogPayload decode(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        long sequence = buf.readVarLong();
        Action action = Action.fromId(buf.readByte());
        ResourceLocation tableId = buf.readBoolean() ? buf.readResourceLocation() : null;
        boolean hasFirstUnlockedTime = buf.readBoolean();
        long firstUnlockedGameTime = hasFirstUnlockedTime ? buf.readVarLong() : 0L;
        long firstUnlockedDayTime = hasFirstUnlockedTime ? buf.readVarLong() : 0L;
        boolean hasEntry = buf.readBoolean();
        ResourceLocation itemId = null;
        ResourceLocation structureId = null;
        ResourceLocation biomeId = ResourceLocation.withDefaultNamespace("plains");
        BlockPos pos = BlockPos.ZERO;
        long gameTime = 0L;
        long dayTime = 0L;
        if (hasEntry) {
            if (buf.readBoolean()) {
                itemId = buf.readResourceLocation();
            }
            if (buf.readBoolean()) {
                structureId = buf.readResourceLocation();
            }
            biomeId = buf.readResourceLocation();
            pos = buf.readBlockPos();
            gameTime = buf.readVarLong();
            dayTime = buf.readVarLong();
        }
        return new SyncJournalLogPayload(sessionId, sequence, action, tableId,
                hasFirstUnlockedTime, firstUnlockedGameTime, firstUnlockedDayTime,
                hasEntry, itemId, structureId, biomeId, pos, gameTime, dayTime);
    }
}
