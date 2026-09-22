package com.meteorite.unsuspiciousblock.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/***
 * 每个维度的结构回溯冷却账本，以结构类型和起始区块识别同一个结构起点。
 */
public final class StructureRewindCooldownData extends SavedData {
    private static final String FILE_NAME = "unsuspiciousblock_structure_rewind_cooldowns";
    private static final String ENTRIES_TAG = "entries";
    private static final String STRUCTURE_TAG = "structure";
    private static final String CHUNK_TAG = "chunk";
    private static final String UNTIL_TAG = "until";
    private static final SavedData.Factory<StructureRewindCooldownData> FACTORY = new SavedData.Factory<>(
            StructureRewindCooldownData::new, StructureRewindCooldownData::load, DataFixTypes.LEVEL);

    private final Map<StructureKey, Long> cooldowns = new HashMap<>();

    private StructureRewindCooldownData() {
    }

    public static StructureRewindCooldownData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    // 返回剩余游戏 tick；查询到期记录时顺手清除。
    public long remaining(ResourceLocation structureId, long startChunk, long gameTime) {
        StructureKey key = new StructureKey(structureId, startChunk);
        Long until = cooldowns.get(key);
        if (until == null) {
            return 0L;
        }
        long remaining = until - gameTime;
        if (remaining <= 0L) {
            cooldowns.remove(key);
            setDirty();
            return 0L;
        }
        return remaining;
    }

    // 仅在回溯任务被接纳后记录冷却，并清理已过期的其它结构。
    public void start(ResourceLocation structureId, long startChunk, long gameTime, long duration) {
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= gameTime);
        cooldowns.put(new StructureKey(structureId, startChunk), gameTime + duration);
        setDirty();
    }

    private static StructureRewindCooldownData load(CompoundTag tag, HolderLookup.Provider registries) {
        StructureRewindCooldownData data = new StructureRewindCooldownData();
        ListTag entries = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            ResourceLocation structureId = ResourceLocation.tryParse(entry.getString(STRUCTURE_TAG));
            if (structureId != null && entry.contains(CHUNK_TAG, Tag.TAG_LONG)
                    && entry.contains(UNTIL_TAG, Tag.TAG_LONG)) {
                data.cooldowns.put(new StructureKey(structureId, entry.getLong(CHUNK_TAG)),
                        entry.getLong(UNTIL_TAG));
            }
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        ListTag entries = new ListTag();
        cooldowns.forEach((key, until) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(STRUCTURE_TAG, key.structureId().toString());
            entry.putLong(CHUNK_TAG, key.startChunk());
            entry.putLong(UNTIL_TAG, until);
            entries.add(entry);
        });
        tag.put(ENTRIES_TAG, entries);
        return tag;
    }

    /*** 同一结构起点在该维度内的稳定身份。 */
    private record StructureKey(ResourceLocation structureId, long startChunk) {
    }
}
