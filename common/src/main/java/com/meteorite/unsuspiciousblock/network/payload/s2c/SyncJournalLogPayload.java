package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.migration.JournalNbtMigrator;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 日记日志增量同步包（服务端→客户端）。
 * 将单次操作（设置首解锁元数据、插入条目、清空等）以增量形式同步至客户端，
 * 客户端按 sessionId + sequence 保证操作顺序与幂等性。
 */
public record SyncJournalLogPayload(UUID sessionId,
                                    long sequence,
                                    Action action,
                                    @Nullable ResourceLocation tableId,
                                    CompoundTag data) implements CustomPacketPayload {
    private static final String LOOT_SOURCE_TAG = "loot_source";
    @Deprecated // 向后兼容读取旧NBT，将于 1.5.0 移除
    private static final String LEGACY_TRIGGER_TYPE_TAG = "trigger_type";
    private static final String GAME_TIME_TAG = "game_time";
    private static final String DAY_TIME_TAG = "day_time";
    private static final String ENTRY_ID_TAG = "entry_id";

    public static final Type<SyncJournalLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogPayload> STREAM_CODEC =
            StreamCodec.of(SyncJournalLogPayload::encode, SyncJournalLogPayload::decode);

    // 构建"设置首解锁元数据"操作包
    public static SyncJournalLogPayload setFirstUnlockMeta(UUID sessionId, long sequence, ResourceLocation tableId,
                                                           @Nullable LootSourceType lootSource,
                                                           long firstUnlockedGameTime, long firstUnlockedDayTime) {
        CompoundTag data = new CompoundTag();
        data.putLong(GAME_TIME_TAG, firstUnlockedGameTime);
        data.putLong(DAY_TIME_TAG, firstUnlockedDayTime);
        if (lootSource != null) {
            data.putString(LOOT_SOURCE_TAG, lootSource.id().toString());
        }
        return new SyncJournalLogPayload(sessionId, sequence, Action.SET_FIRST_UNLOCK_META, tableId, data);
    }

    // 构建"插入/更新条目"操作包
    public static SyncJournalLogPayload upsertEntry(UUID sessionId, long sequence, ResourceLocation tableId,
                                                    CompoundTag entryTag) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.UPSERT_ENTRY, tableId, entryTag.copy());
    }

    // 构建"清空全部日志"操作包
    public static SyncJournalLogPayload clearAll(UUID sessionId, long sequence) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.CLEAR_ALL, null, new CompoundTag());
    }

    // 构建"清空指定表日志"操作包
    public static SyncJournalLogPayload clearTable(UUID sessionId, long sequence, ResourceLocation tableId) {
        return new SyncJournalLogPayload(sessionId, sequence, Action.CLEAR_TABLE, tableId, new CompoundTag());
    }

    // 构建“删除单条日志”操作包
    public static SyncJournalLogPayload deleteEntry(UUID sessionId, long sequence,
                                                     ResourceLocation tableId, UUID entryId) {
        CompoundTag data = new CompoundTag();
        data.putUUID(ENTRY_ID_TAG, entryId);
        return new SyncJournalLogPayload(sessionId, sequence, Action.DELETE_ENTRY, tableId, data);
    }

    @Override
    public @NotNull Type<SyncJournalLogPayload> type() {
        return TYPE;
    }

    // 从包数据中解析战利品来源类型
    @Nullable
    public LootSourceType lootSource() {
        if (this.data.contains(LOOT_SOURCE_TAG, Tag.TAG_STRING)) {
            return LootSourceType.fromId(this.data.getString(LOOT_SOURCE_TAG));
        }
        // 向后兼容：读取旧字段名
        if (this.data.contains(LEGACY_TRIGGER_TYPE_TAG, Tag.TAG_STRING)) {
            return JournalNbtMigrator.parseLegacyLootSource(this.data.getString(LEGACY_TRIGGER_TYPE_TAG));
        }
        return null;
    }

    // 从包数据中获取游戏时间（非负）
    public long gameTime() {
        return Math.max(0L, this.data.getLong(GAME_TIME_TAG));
    }

    // 从包数据中获取日时间，若不存在则回退为游戏时间
    public long dayTime() {
        return this.data.contains(DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, this.data.getLong(DAY_TIME_TAG))
                : this.gameTime();
    }

    @Nullable
    public UUID entryId() {
        return this.data.hasUUID(ENTRY_ID_TAG) ? this.data.getUUID(ENTRY_ID_TAG) : null;
    }

    // 日志同步操作类型
    public enum Action {
        SET_FIRST_UNLOCK_META(0),
        UPSERT_ENTRY(1),
        CLEAR_ALL(2),
        CLEAR_TABLE(3),
        DELETE_ENTRY(4);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        // 根据数字 ID 解析操作类型，默认返回 UPSERT_ENTRY
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
