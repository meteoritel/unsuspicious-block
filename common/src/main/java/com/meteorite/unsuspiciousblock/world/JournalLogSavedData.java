package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * 考古日志持久化数据——按玩家 UUID 索引存储各玩家的 ArchaeologyJournalLogState。
 * <p>
 * 存储位置：overworld 的 data 目录下，文件名 {@value #FILE_NAME}。
 * 数据流：登录时从本类读取状态并交给 session(共享引用)；每次变更后由 JournalLogHandler
 * <p>
 * 不变式：玩家在线时，本类持有的状态对象与 {@code ArchaeologyJournalLogSyncSession.mirroredState}
 * 为同一引用，因此对 session 镜像的修改即等同于修改持久化数据。
 * <p>
 * 取代旧的 PlayerJournalLogStateMixin 玩家 NBT 持久化方案，避免玩家 NBT 因日志条目过多而膨胀。
 */
public final class JournalLogSavedData extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(JournalLogSavedData.class);
    private static final String FILE_NAME = "unsuspiciousblock_journal_logs";
    private static final String TAG_PLAYERS = "players";

    private static final SavedData.Factory<JournalLogSavedData> FACTORY = new SavedData.Factory<>(
            JournalLogSavedData::new,
            JournalLogSavedData::load,
            DataFixTypes.LEVEL
    );

    // 玩家 UUID -> 日志状态；所有访问在主线程，无需并发控制
    private final Map<UUID, ArchaeologyJournalLogState> playerStates = new HashMap<>();

    // 获取 overworld 范围内的全局单例
    public static JournalLogSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private JournalLogSavedData() {
    }

    // 从 NBT 加载全部玩家的日志状态
    private static JournalLogSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        JournalLogSavedData data = new JournalLogSavedData();
        if (tag.contains(TAG_PLAYERS, Tag.TAG_COMPOUND)) {
            CompoundTag playersTag = tag.getCompound(TAG_PLAYERS);
            for (String key : playersTag.getAllKeys()) {
                UUID uuid = parseUuid(key);
                if (uuid == null) {
                    continue;
                }
                ArchaeologyJournalLogState state = new ArchaeologyJournalLogState();
                state.readFrom(playersTag.getCompound(key));
                data.playerStates.put(uuid, state);
            }
        }
        LOGGER.debug("从存档加载了 {} 个玩家的考古日志数据", data.playerStates.size());
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, ArchaeologyJournalLogState> entry : playerStates.entrySet()) {
            playersTag.put(entry.getKey().toString(), entry.getValue().toTag());
        }
        tag.put(TAG_PLAYERS, playersTag);
        return tag;
    }

    // 仅供存储格式 v1 -> v2 迁移读取；返回的状态仍由本 SavedData 持有
    public Map<UUID, ArchaeologyJournalLogState> snapshotStatesForMigration() {
        return Collections.unmodifiableMap(this.playerStates);
    }

    @Nullable
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("无法解析玩家 UUID: {}", value);
            return null;
        }
    }
}
