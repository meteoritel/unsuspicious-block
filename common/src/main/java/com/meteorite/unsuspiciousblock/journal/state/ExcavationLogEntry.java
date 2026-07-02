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
 * 包含战利品来源类型、位置、生物群系、所在结构、
 * 预期的战利品（expectedLoot）与实际获取的战利品（actualLoot）。
 * 支持 NBT 序列化与双向同步。
 */
public record ExcavationLogEntry(UUID entryId,
                                 @Nullable LootSourceType lootSource,
                                 ExcavationLogEntry.ExcavationContext context,
                                 ExcavationLogEntry.GameTimestamp created,
                                 ExcavationLogEntry.GameTimestamp lastUpdated,
                                 Map<String, Integer> expectedLoot,
                                 Map<String, Integer> actualLoot,
                                 String note) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcavationLogEntry.class);

    private static final String ENTRY_ID_TAG = "entry_id";
    private static final String DIMENSION_ID_TAG = "dimension_id";
    private static final String LOOT_SOURCE_TAG = "loot_source";
    @Deprecated // 向后兼容读取旧NBT
    private static final String LEGACY_TRIGGER_TYPE_TAG = "trigger_type";
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
    private static final String NOTE_TAG = "note";

    /** 位置上下文：维度、源方块、结构、生物群系、坐标 */
    public record ExcavationContext(@Nullable ResourceLocation dimensionId,
                                    @Nullable ResourceLocation sourceBlockId,
                                    @Nullable ResourceLocation structureId,
                                    ResourceLocation biomeId,
                                    BlockPos pos) {
        public ExcavationContext {
            if (dimensionId == null) {
                dimensionId = ResourceLocation.withDefaultNamespace("overworld");
            }
            if (biomeId == null) {
                biomeId = ResourceLocation.withDefaultNamespace("plains");
            }
            if (pos == null) {
                pos = BlockPos.ZERO;
            }
        }
    }

    /** 游戏时间戳：游戏刻 + 日间时间 */
    public record GameTimestamp(long gameTime, long dayTime) {
        public GameTimestamp {
            if (gameTime < 0) gameTime = 0;
            if (dayTime < 0) dayTime = 0;
        }
    }

    // 便利访问器——保持向后兼容
    public ResourceLocation dimensionId() { return context.dimensionId(); }
    @Nullable
    public ResourceLocation sourceBlockId() { return context.sourceBlockId(); }
    @Nullable
    public ResourceLocation structureId() { return context.structureId(); }
    public ResourceLocation biomeId() { return context.biomeId(); }
    public BlockPos pos() { return context.pos(); }
    public long createdGameTime() { return created.gameTime(); }
    public long createdDayTime() { return created.dayTime(); }
    public long lastUpdatedGameTime() { return lastUpdated.gameTime(); }
    public long lastUpdatedDayTime() { return lastUpdated.dayTime(); }
    /** 等同于 createdGameTime，保持旧 API 兼容 */
    public long gameTime() { return created.gameTime(); }
    /** 等同于 createdDayTime，保持旧 API 兼容 */
    public long dayTime() { return created.dayTime(); }

    public ExcavationLogEntry {
        if (context == null) {
            context = new ExcavationContext(null, null, null, null, null);
        }
        if (created == null) {
            created = new GameTimestamp(0, 0);
        }
        if (lastUpdated == null) {
            lastUpdated = created;
        } else if (lastUpdated.gameTime() < created.gameTime()
                || lastUpdated.gameTime() == created.gameTime() && lastUpdated.dayTime() < created.dayTime()) {
            lastUpdated = created;
        }
        expectedLoot = LootCounts.normalize(expectedLoot);
        actualLoot = LootCounts.normalize(actualLoot);
        if (note == null) {
            note = "";
        }
    }

    // 省略 note 参数的便利构造器，默认无备注
    public ExcavationLogEntry(UUID entryId,
                              @Nullable LootSourceType lootSource,
                              ExcavationLogEntry.ExcavationContext context,
                              ExcavationLogEntry.GameTimestamp created,
                              ExcavationLogEntry.GameTimestamp lastUpdated,
                              Map<String, Integer> expectedLoot,
                              Map<String, Integer> actualLoot) {
        this(entryId, lootSource, context, created, lastUpdated, expectedLoot, actualLoot, "");
    }

    /** 是否存在备注内容 */
    public boolean hasNote() {
        return note != null && !note.isEmpty();
    }

    // 返回带有新备注的副本，其他字段保持不变
    public ExcavationLogEntry withNote(String newNote) {
        return new ExcavationLogEntry(this.entryId, this.lootSource, this.context,
                this.created, this.lastUpdated,
                this.expectedLoot, this.actualLoot, newNote == null ? "" : newNote);
    }

    public ExcavationLogEntry withActualLootMerged(Map<String, Integer> deltaLoot,
                                                   long updatedGameTime, long updatedDayTime) {
        LinkedHashMap<String, Integer> mergedActualLoot = new LinkedHashMap<>(this.actualLoot);
        LootCounts.mergeInto(mergedActualLoot, deltaLoot);
        return new ExcavationLogEntry(this.entryId, this.lootSource, this.context,
                this.created, new GameTimestamp(updatedGameTime, updatedDayTime),
                this.expectedLoot, mergedActualLoot, this.note);
    }

    @Nullable
    public ResourceLocation itemId() {
        ResourceLocation actualItemId = firstItemId(this.actualLoot);
        if (actualItemId != null) {
            return actualItemId;
        }
        return firstItemId(this.expectedLoot);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(ENTRY_ID_TAG, this.entryId.toString());
        if (this.lootSource != null) {
            tag.putString(LOOT_SOURCE_TAG, this.lootSource.serializedName());
        }
        if (this.context.dimensionId != null) {
            tag.putString(DIMENSION_ID_TAG, this.context.dimensionId.toString());
        }
        if (this.context.sourceBlockId != null) {
            tag.putString(SOURCE_BLOCK_ID_TAG, this.context.sourceBlockId.toString());
        }
        if (this.context.structureId != null) {
            tag.putString(STRUCTURE_ID_TAG, this.context.structureId.toString());
        }
        tag.putString(BIOME_ID_TAG, this.context.biomeId.toString());
        tag.putInt(POS_X_TAG, this.context.pos.getX());
        tag.putInt(POS_Y_TAG, this.context.pos.getY());
        tag.putInt(POS_Z_TAG, this.context.pos.getZ());
        tag.putLong(CREATED_GAME_TIME_TAG, this.created.gameTime);
        tag.putLong(CREATED_DAY_TIME_TAG, this.created.dayTime);
        tag.putLong(LAST_UPDATED_GAME_TIME_TAG, this.lastUpdated.gameTime);
        tag.putLong(LAST_UPDATED_DAY_TIME_TAG, this.lastUpdated.dayTime);
        tag.put(EXPECTED_LOOT_TAG, LootCounts.writeToNbt(this.expectedLoot));
        tag.put(ACTUAL_LOOT_TAG, LootCounts.writeToNbt(this.actualLoot));
        if (this.note != null && !this.note.isEmpty()) {
            tag.putString(NOTE_TAG, this.note);
        }
        return tag;
    }

    public static ExcavationLogEntry fromTag(CompoundTag tag) {
        UUID entryId = parseUuid(tag.getString(ENTRY_ID_TAG));
        if (entryId == null) {
            entryId = UUID.randomUUID();
        }
        // 向后兼容：优先读取新字段 loot_source，其次读取旧字段 trigger_type
        LootSourceType lootSource = null;
        if (tag.contains(LOOT_SOURCE_TAG, Tag.TAG_STRING)) {
            lootSource = LootSourceType.fromSerializedName(tag.getString(LOOT_SOURCE_TAG));
        } else if (tag.contains(LEGACY_TRIGGER_TYPE_TAG, Tag.TAG_STRING)) {
            lootSource = LootSourceType.fromSerializedName(tag.getString(LEGACY_TRIGGER_TYPE_TAG));
        }
        ResourceLocation sourceBlockId = tag.contains(SOURCE_BLOCK_ID_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(SOURCE_BLOCK_ID_TAG))
                : null;
        ResourceLocation structureId = tag.contains(STRUCTURE_ID_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(STRUCTURE_ID_TAG))
                : null;
        ResourceLocation dimensionId = tag.contains(DIMENSION_ID_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(DIMENSION_ID_TAG))
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
        String note = tag.contains(NOTE_TAG, Tag.TAG_STRING) ? tag.getString(NOTE_TAG) : "";

        ExcavationContext context = new ExcavationContext(dimensionId, sourceBlockId, structureId, biomeId, pos);
        GameTimestamp created = new GameTimestamp(createdGameTime, createdDayTime);
        GameTimestamp lastUpdated = new GameTimestamp(lastUpdatedGameTime, lastUpdatedDayTime);
        return new ExcavationLogEntry(entryId, lootSource, context, created, lastUpdated, expectedLoot, actualLoot, note);
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