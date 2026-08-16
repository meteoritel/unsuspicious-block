package com.meteorite.unsuspiciousblock.journal.state;

import com.meteorite.unsuspiciousblock.journal.migration.JournalDataVersion;
import com.meteorite.unsuspiciousblock.journal.migration.JournalNbtMigrator;

import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单条考古日志记录——记录一次发掘事件的全部上下文。
 * 包含战利品来源类型、位置、生物群系、所在结构、
 * 预期的战利品（expectedLoot）与实际获取的战利品（actualLoot）。
 * {@code tableStack} 记录嵌套战利品表的完整链路（根表→子表→...），缺失时视为单层。
 * 支持 NBT 序列化与双向同步。
 */
public record ExcavationLogEntry(UUID entryId,
                                 @Nullable LootSourceType lootSource,
                                 ExcavationLogEntry.ExcavationContext context,
                                 ExcavationLogEntry.GameTimestamp created,
                                 ExcavationLogEntry.GameTimestamp lastUpdated,
                                 Map<String, Integer> expectedLoot,
                                 Map<String, Integer> actualLoot,
                                 String note,
                                 @Nullable List<ResourceLocation> tableStack) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcavationLogEntry.class);

    private static final String ENTRY_ID_TAG = "entry_id";
    private static final String DIMENSION_ID_TAG = "dimension_id";
    private static final String LOOT_SOURCE_TAG = "loot_source";
    private static final String SOURCE_BLOCK_ID_TAG = "source_block_id";
    private static final String STRUCTURE_ID_TAG = "structure_id";
    private static final String BIOME_ID_TAG = "biome_id";
    private static final String POS_X_TAG = "pos_x";
    private static final String POS_Y_TAG = "pos_y";
    private static final String POS_Z_TAG = "pos_z";
    private static final String CREATED_GAME_TIME_TAG = "created_game_time";
    private static final String CREATED_DAY_TIME_TAG = "created_day_time";
    private static final String LAST_UPDATED_GAME_TIME_TAG = "last_updated_game_time";
    private static final String LAST_UPDATED_DAY_TIME_TAG = "last_updated_day_time";
    private static final String EXPECTED_LOOT_TAG = "expected_loot";
    private static final String ACTUAL_LOOT_TAG = "actual_loot";
    private static final String NOTE_TAG = "note";
    private static final String TABLE_STACK_TAG = "table_stack";

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
        // tableStack 保持 null 或不可变副本
        if (tableStack != null) {
            tableStack = List.copyOf(tableStack);
        }
    }

    // 省略 tableStack 参数的便利构造器（向后兼容），tableStack 默认 null
    public ExcavationLogEntry(UUID entryId,
                              @Nullable LootSourceType lootSource,
                              ExcavationLogEntry.ExcavationContext context,
                              ExcavationLogEntry.GameTimestamp created,
                              ExcavationLogEntry.GameTimestamp lastUpdated,
                              Map<String, Integer> expectedLoot,
                              Map<String, Integer> actualLoot,
                              String note) {
        this(entryId, lootSource, context, created, lastUpdated, expectedLoot, actualLoot, note, null);
    }

    // 省略 note 与 tableStack 参数的便利构造器（向后兼容）
    public ExcavationLogEntry(UUID entryId,
                              @Nullable LootSourceType lootSource,
                              ExcavationLogEntry.ExcavationContext context,
                              ExcavationLogEntry.GameTimestamp created,
                              ExcavationLogEntry.GameTimestamp lastUpdated,
                              Map<String, Integer> expectedLoot,
                              Map<String, Integer> actualLoot) {
        this(entryId, lootSource, context, created, lastUpdated, expectedLoot, actualLoot, "", null);
    }

    /** 是否存在备注内容 */
    public boolean hasNote() {
        return note != null && !note.isEmpty();
    }

    // 返回带有新备注的副本，其他字段保持不变
    public ExcavationLogEntry withNote(String newNote) {
        return new ExcavationLogEntry(this.entryId, this.lootSource, this.context,
                this.created, this.lastUpdated,
                this.expectedLoot, this.actualLoot, newNote == null ? "" : newNote, this.tableStack);
    }

    // 返回带有新 tableStack 的副本，其他字段保持不变
    public ExcavationLogEntry withTableStack(@Nullable List<ResourceLocation> newTableStack) {
        return new ExcavationLogEntry(this.entryId, this.lootSource, this.context,
                this.created, this.lastUpdated,
                this.expectedLoot, this.actualLoot, this.note, newTableStack);
    }

    public ExcavationLogEntry withActualLootMerged(Map<String, Integer> deltaLoot,
                                                   long updatedGameTime, long updatedDayTime) {
        LinkedHashMap<String, Integer> mergedActualLoot = new LinkedHashMap<>(this.actualLoot);
        LootCounts.mergeInto(mergedActualLoot, deltaLoot);
        return new ExcavationLogEntry(this.entryId, this.lootSource, this.context,
                this.created, new GameTimestamp(updatedGameTime, updatedDayTime),
                this.expectedLoot, mergedActualLoot, this.note, this.tableStack);
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
        tag.putInt(JournalDataVersion.NBT_VERSION_TAG, JournalDataVersion.CURRENT_NBT_VERSION);
        tag.putString(ENTRY_ID_TAG, this.entryId.toString());
        if (this.lootSource != null) {
            tag.putString(LOOT_SOURCE_TAG, this.lootSource.id().toString());
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
        // tableStack 非空时写入 ListTag<String>；缺失时 fromTag 返回 null（向后兼容旧 NBT）
        if (this.tableStack != null && !this.tableStack.isEmpty()) {
            ListTag stackTag = new ListTag();
            for (ResourceLocation tableId : this.tableStack) {
                stackTag.add(StringTag.valueOf(tableId.toString()));
            }
            tag.put(TABLE_STACK_TAG, stackTag);
        }
        return tag;
    }

    public static ExcavationLogEntry fromTag(CompoundTag tag) {
        JournalNbtMigrator.migrateExcavationLogEntry(tag);
        UUID entryId = parseUuid(tag.getString(ENTRY_ID_TAG));
        if (entryId == null) {
            entryId = UUID.randomUUID();
        }
        LootSourceType lootSource = null;
        if (tag.contains(LOOT_SOURCE_TAG, Tag.TAG_STRING)) {
            lootSource = LootSourceType.fromId(tag.getString(LOOT_SOURCE_TAG));
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

        long createdGameTime = tag.contains(CREATED_GAME_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(CREATED_GAME_TIME_TAG))
                : 0L;
        long createdDayTime = tag.contains(CREATED_DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(CREATED_DAY_TIME_TAG))
                : createdGameTime;
        long lastUpdatedGameTime = tag.contains(LAST_UPDATED_GAME_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(LAST_UPDATED_GAME_TIME_TAG))
                : createdGameTime;
        long lastUpdatedDayTime = tag.contains(LAST_UPDATED_DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(LAST_UPDATED_DAY_TIME_TAG))
                : createdDayTime;

        Map<String, Integer> expectedLoot = tag.contains(EXPECTED_LOOT_TAG, Tag.TAG_COMPOUND)
                ? LootCounts.readFromNbt(tag.getCompound(EXPECTED_LOOT_TAG))
                : Map.of();
        Map<String, Integer> actualLoot = tag.contains(ACTUAL_LOOT_TAG, Tag.TAG_COMPOUND)
                ? LootCounts.readFromNbt(tag.getCompound(ACTUAL_LOOT_TAG))
                : Map.of();
        String note = tag.contains(NOTE_TAG, Tag.TAG_STRING) ? tag.getString(NOTE_TAG) : "";

        // tableStack：向后兼容，缺失时返回 null（视为单层根表）
        List<ResourceLocation> tableStack = null;
        if (tag.contains(TABLE_STACK_TAG, Tag.TAG_LIST)) {
            ListTag stackTag = tag.getList(TABLE_STACK_TAG, Tag.TAG_STRING);
            if (!stackTag.isEmpty()) {
                tableStack = new ArrayList<>(stackTag.size());
                for (int i = 0; i < stackTag.size(); i++) {
                    ResourceLocation tableId = ResourceLocation.tryParse(stackTag.getString(i));
                    if (tableId != null) {
                        tableStack.add(tableId);
                    }
                }
                if (tableStack.isEmpty()) {
                    tableStack = null;
                }
            }
        }

        ExcavationContext context = new ExcavationContext(dimensionId, sourceBlockId, structureId, biomeId, pos);
        GameTimestamp created = new GameTimestamp(createdGameTime, createdDayTime);
        GameTimestamp lastUpdated = new GameTimestamp(lastUpdatedGameTime, lastUpdatedDayTime);
        return new ExcavationLogEntry(entryId, lootSource, context, created, lastUpdated,
                expectedLoot, actualLoot, note, tableStack);
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
