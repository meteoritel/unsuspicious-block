package com.meteorite.unsuspiciousblock.network.payload;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.TriggerType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
                                    CompoundTag data) implements CustomPacketPayload {
    private static final String TRIGGER_TYPE_TAG = "trigger_type";
    private static final String GAME_TIME_TAG = "game_time";
    private static final String DAY_TIME_TAG = "day_time";

    public static final Type<SyncJournalLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogPayload> STREAM_CODEC =
            StreamCodec.of(SyncJournalLogPayload::encode, SyncJournalLogPayload::decode);

    public static SyncJournalLogPayload setFirstUnlockMeta(UUID sessionId, long sequence, ResourceLocation tableId,
                                                           @Nullable TriggerType triggerType,
                                                           long firstUnlockedGameTime, long firstUnlockedDayTime) {
        CompoundTag data = new CompoundTag();
        data.putLong(GAME_TIME_TAG, firstUnlockedGameTime);
        data.putLong(DAY_TIME_TAG, firstUnlockedDayTime);
        if (triggerType != null) {
            data.putString(TRIGGER_TYPE_TAG, triggerType.serializedName());
        }
        return new SyncJournalLogPayload(sessionId, sequence, Action.SET_FIRST_UNLOCK_META, tableId, data);
    }

    public static SyncJournalLogPayload upsertEntry(UUID sessionId, long sequence, ResourceLocation tableId,
                                                    CompoundTag entryTag) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.UPSERT_ENTRY, tableId, entryTag.copy());
    }

    public static SyncJournalLogPayload clearAll(UUID sessionId, long sequence) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.CLEAR_ALL, null, new CompoundTag());
    }

    public static SyncJournalLogPayload clearTable(UUID sessionId, long sequence, ResourceLocation tableId) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.CLEAR_TABLE, tableId, new CompoundTag());
    }

    @Override
    public @NotNull Type<SyncJournalLogPayload> type() {
        return TYPE;
    }

    @Nullable
    public TriggerType triggerType() {
        if (!this.data.contains(TRIGGER_TYPE_TAG, Tag.TAG_STRING)) {
            return null;
        }
        return TriggerType.fromSerializedName(this.data.getString(TRIGGER_TYPE_TAG));
    }

    public long gameTime() {
        return Math.max(0L, this.data.getLong(GAME_TIME_TAG));
    }

    public long dayTime() {
        return this.data.contains(DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, this.data.getLong(DAY_TIME_TAG))
                : this.gameTime();
    }

    public enum Action {
        SET_FIRST_UNLOCK_META(0),
        UPSERT_ENTRY(1),
        CLEAR_ALL(2),
        CLEAR_TABLE(3);

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
            return UPSERT_ENTRY;
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
        buf.writeNbt(payload.data);
    }

    private static SyncJournalLogPayload decode(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        long sequence = buf.readVarLong();
        Action action = Action.fromId(buf.readByte());
        ResourceLocation tableId = buf.readBoolean() ? buf.readResourceLocation() : null;
        CompoundTag data = buf.readNbt();
        return new SyncJournalLogPayload(sessionId, sequence, action, tableId, data != null ? data : new CompoundTag());
    }
}
