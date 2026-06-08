package com.meteorite.unsuspiciousblock.journal.state;

import com.meteorite.unsuspiciousblock.loottable.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单条考古日志记录——记录一次发掘事件的全部上下文。
 * 包含触发类型、源方块、位置、生物群系、所在结构、
 * 预期的战利品（expectedLoot）与实际获取的战利品（actualLoot）。
 * 支持 NBT 序列化与双向同步。
 */
public record ExcavationLogEntry(UUID entryId,
                                 @Nullable TriggerType triggerType,
                                 @Nullable ResourceLocation sourceBlockId,
                                 @Nullable ResourceLocation structureId,
                                 ResourceLocation biomeId,
                                 BlockPos pos,
                                 long createdGameTime,
                                 long createdDayTime,
                                 long lastUpdatedGameTime,
                                 long lastUpdatedDayTime,
                                 Map<String, Integer> expectedLoot,
                                 Map<String, Integer> actualLoot) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcavationLogEntry.class);

    private static final String ENTRY_ID_TAG = "entry_id";
    private static final String TRIGGER_TYPE_TAG = "trigger_type";
    private static final String SOURCE_BLOCK_ID_TAG = "source_block_id";
    private static final String ITEM_ID_TAG = "item_id";
    private static final String STRUCTURE_ID_TAG = "structure_id";
    private static final String BIOME_ID_TAG = "biome_id";
    private static final String POS_X_TAG = "pos_x";
    private static final String POS_Y_TAG = "pos_y";
    private static final String POS_Z_TAG = "pos_z";
    private static final String GAME_TIME_TAG = "game_time";
    private static final String DAY_TIME_TAG = "day_time";
    private static final String CREATED_GAME_TIME_TAG = "created_game_time";
    private static final String CREATED_DAY_TIME_TAG = "created_day_time";
    private static final String LAST_UPDATED_GAME_TIME_TAG = "last_updated_game_time";
    private static final String LAST_UPDATED_DAY_TIME_TAG = "last_updated_day_time";
    private static final String EXPECTED_LOOT_TAG = "expected_loot";
    private static final String ACTUAL_LOOT_TAG = "actual_loot";

    public ExcavationLogEntry {
        if (biomeId == null) {
            biomeId = ResourceLocation.withDefaultNamespace("plains");
        }
        if (pos == null) {
            pos = BlockPos.ZERO;
        }

        createdGameTime = Math.max(0L, createdGameTime);
        createdDayTime = Math.max(0L, createdDayTime);
        lastUpdatedGameTime = Math.max(0L, lastUpdatedGameTime);
        lastUpdatedDayTime = Math.max(0L, lastUpdatedDayTime);
        if (lastUpdatedGameTime < createdGameTime
                || lastUpdatedGameTime == createdGameTime && lastUpdatedDayTime < createdDayTime) {
            lastUpdatedGameTime = createdGameTime;
            lastUpdatedDayTime = createdDayTime;
        }

        expectedLoot = LootCounts.normalize(expectedLoot);
        actualLoot = LootCounts.normalize(actualLoot);
    }

    public ExcavationLogEntry withActualLootMerged(Map<String, Integer> deltaLoot,
                                                   long updatedGameTime, long updatedDayTime) {
        LinkedHashMap<String, Integer> mergedActualLoot = new LinkedHashMap<>(this.actualLoot);
        LootCounts.mergeInto(mergedActualLoot, deltaLoot);
        return new ExcavationLogEntry(this.entryId, this.triggerType, this.sourceBlockId, this.structureId,
                this.biomeId, this.pos, this.createdGameTime, this.createdDayTime,
                updatedGameTime, updatedDayTime, this.expectedLoot, mergedActualLoot);
    }

    @Nullable
    public ResourceLocation itemId() {
        ResourceLocation actualItemId = firstItemId(this.actualLoot);
        if (actualItemId != null) {
            return actualItemId;
        }
        return firstItemId(this.expectedLoot);
    }

    public long gameTime() {
        return this.createdGameTime;
    }

    public long dayTime() {
        return this.createdDayTime;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(ENTRY_ID_TAG, this.entryId.toString());
        if (this.triggerType != null) {
            tag.putString(TRIGGER_TYPE_TAG, this.triggerType.serializedName());
        }
        if (this.sourceBlockId != null) {
            tag.putString(SOURCE_BLOCK_ID_TAG, this.sourceBlockId.toString());
        }
        if (this.structureId != null) {
            tag.putString(STRUCTURE_ID_TAG, this.structureId.toString());
        }
        tag.putString(BIOME_ID_TAG, this.biomeId.toString());
        tag.putInt(POS_X_TAG, this.pos.getX());
        tag.putInt(POS_Y_TAG, this.pos.getY());
        tag.putInt(POS_Z_TAG, this.pos.getZ());
        tag.putLong(CREATED_GAME_TIME_TAG, this.createdGameTime);
        tag.putLong(CREATED_DAY_TIME_TAG, this.createdDayTime);
        tag.putLong(LAST_UPDATED_GAME_TIME_TAG, this.lastUpdatedGameTime);
        tag.putLong(LAST_UPDATED_DAY_TIME_TAG, this.lastUpdatedDayTime);
        tag.put(EXPECTED_LOOT_TAG, LootCounts.writeToNbt(this.expectedLoot));
        tag.put(ACTUAL_LOOT_TAG, LootCounts.writeToNbt(this.actualLoot));
        return tag;
    }

    public static ExcavationLogEntry fromTag(CompoundTag tag) {
        UUID entryId = parseUuid(tag.getString(ENTRY_ID_TAG));
        if (entryId == null) {
            entryId = UUID.randomUUID();
        }
        TriggerType triggerType = tag.contains(TRIGGER_TYPE_TAG, Tag.TAG_STRING)
                ? TriggerType.fromSerializedName(tag.getString(TRIGGER_TYPE_TAG))
                : null;
        ResourceLocation sourceBlockId = tag.contains(SOURCE_BLOCK_ID_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(SOURCE_BLOCK_ID_TAG))
                : null;
        ResourceLocation structureId = tag.contains(STRUCTURE_ID_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(STRUCTURE_ID_TAG))
                : null;
        ResourceLocation biomeId = ResourceLocation.tryParse(tag.getString(BIOME_ID_TAG));
        if (biomeId == null) {
            biomeId = ResourceLocation.withDefaultNamespace("plains");
        }
        BlockPos pos = new BlockPos(tag.getInt(POS_X_TAG), tag.getInt(POS_Y_TAG), tag.getInt(POS_Z_TAG));

        long legacyGameTime = Math.max(0L, tag.getLong(GAME_TIME_TAG));
        long legacyDayTime = tag.contains(DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(DAY_TIME_TAG))
                : legacyGameTime;
        long createdGameTime = tag.contains(CREATED_GAME_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(CREATED_GAME_TIME_TAG))
                : legacyGameTime;
        long createdDayTime = tag.contains(CREATED_DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(CREATED_DAY_TIME_TAG))
                : legacyDayTime;
        long lastUpdatedGameTime = tag.contains(LAST_UPDATED_GAME_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(LAST_UPDATED_GAME_TIME_TAG))
                : createdGameTime;
        long lastUpdatedDayTime = tag.contains(LAST_UPDATED_DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(LAST_UPDATED_DAY_TIME_TAG))
                : createdDayTime;

        ResourceLocation legacyItemId = tag.contains(ITEM_ID_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(ITEM_ID_TAG))
                : null;
        Map<String, Integer> expectedLoot = tag.contains(EXPECTED_LOOT_TAG, Tag.TAG_COMPOUND)
                ? LootCounts.readFromNbt(tag.getCompound(EXPECTED_LOOT_TAG))
                : createLegacyLootMap(legacyItemId);
        Map<String, Integer> actualLoot = tag.contains(ACTUAL_LOOT_TAG, Tag.TAG_COMPOUND)
                ? LootCounts.readFromNbt(tag.getCompound(ACTUAL_LOOT_TAG))
                : createLegacyLootMap(legacyItemId);
        return new ExcavationLogEntry(entryId, triggerType, sourceBlockId, structureId, biomeId, pos,
                createdGameTime, createdDayTime, lastUpdatedGameTime, lastUpdatedDayTime,
                expectedLoot, actualLoot);
    }

    @Nullable
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Failed to parse UUID from NBT: {}", value, e);
            return null;
        }
    }

    private static Map<String, Integer> createLegacyLootMap(@Nullable ResourceLocation itemId) {
        if (itemId == null) {
            return Map.of();
        }
        return Map.of(LootResultSignature.plain(itemId).toStoredKey(), 1);
    }

    @Nullable
    private static ResourceLocation firstItemId(Map<String, Integer> lootMap) {
        for (String signatureKey : lootMap.keySet()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
            if (signature != null) {
                return signature.itemId();
            }
        }
        return null;
    }
}