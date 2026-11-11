package com.meteorite.unsuspiciousblock.core.journal.migration;

import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootCounts;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.persistence.migration.MigrationVersions;
import com.meteorite.unsuspiciousblock.core.persistence.migration.NbtMigration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 考古日志 NBT schema 迁移器。
 * <p>
 * 所有旧字段识别和逐版本转换只允许定义在本类。状态类的反序列化方法只读取
 * 当前格式，避免未来升级时在多个 {@code fromTag()} 中重复维护兼容分支。
 */
public final class JournalNbtMigrator {
    private static final String TABLES_TAG = "tables";
    private static final String ENTRIES_TAG = "entries";
    private static final String FIRST_UNLOCK_LOOT_SOURCE_TAG = "first_unlock_loot_source";
    private static final String LEGACY_FIRST_UNLOCK_TRIGGER_TYPE_TAG = "first_unlock_trigger_type";
    private static final String LOOT_SOURCE_TAG = "loot_source";
    private static final String LEGACY_TRIGGER_TYPE_TAG = "trigger_type";
    private static final String DIMENSION_ID_TAG = "dimension_id";
    private static final String LEGACY_ITEM_ID_TAG = "item_id";
    private static final String LEGACY_GAME_TIME_TAG = "game_time";
    private static final String LEGACY_DAY_TIME_TAG = "day_time";
    private static final String CREATED_GAME_TIME_TAG = "created_game_time";
    private static final String CREATED_DAY_TIME_TAG = "created_day_time";
    private static final String LAST_UPDATED_GAME_TIME_TAG = "last_updated_game_time";
    private static final String LAST_UPDATED_DAY_TIME_TAG = "last_updated_day_time";
    private static final String EXPECTED_LOOT_TAG = "expected_loot";
    private static final String ACTUAL_LOOT_TAG = "actual_loot";
    private static final String LIFETIME_ENTRY_COUNT_TAG = "lifetime_entry_count";
    private static final String RETENTION_LIMIT_TAG = "retention_limit";

    private static final NbtMigration JOURNAL_STATE = migration("ArchaeologyJournalState",
            Map.of(0, tag -> {}, 1, tag -> {}));
    private static final NbtMigration JOURNAL_LOG_STATE = migration("ArchaeologyJournalLogState", Map.of(
            0, tag -> migrateTables(tag, JournalNbtMigrator::migrateTableLogHistoryV0ToV1,
                    JournalDataVersion.NBT_VERSION_LOOT_SOURCE),
            1, tag -> migrateTables(tag, JournalNbtMigrator::migrateTableLogHistoryV1ToV2,
                    JournalDataVersion.NBT_VERSION_LOG_RETENTION)));
    private static final NbtMigration TABLE_LOG_HISTORY = migration("TableLogHistory", Map.of(
            0, JournalNbtMigrator::migrateTableLogHistoryV0ToV1,
            1, JournalNbtMigrator::migrateTableLogHistoryV1ToV2));
    private static final NbtMigration EXCAVATION_LOG_ENTRY = migration("ExcavationLogEntry", Map.of(
            0, JournalNbtMigrator::migrateExcavationLogEntryV0ToV1,
            1, tag -> {}));

    private JournalNbtMigrator() {
    }

    /** 迁移玩家目录进度，旧版本没有字段变化，沿共享 NBT 版本轴补齐标记。 */
    public static void migrateJournalState(CompoundTag tag) {
        JOURNAL_STATE.migrate(tag);
    }

    /** 迁移完整日志状态；失败时保留输入 NBT。 */
    public static void migrateJournalLogState(CompoundTag tag) {
        JOURNAL_LOG_STATE.migrate(tag);
    }

    /** 迁移单表日志；旧字段规则与完整日志使用同一实现。 */
    public static void migrateTableLogHistory(CompoundTag tag) {
        TABLE_LOG_HISTORY.migrate(tag);
    }

    /** 迁移独立日志条目，沿用条目的现有版本标记。 */
    public static void migrateExcavationLogEntry(CompoundTag tag) {
        EXCAVATION_LOG_ENTRY.migrate(tag);
    }

    private static NbtMigration migration(String name, Map<Integer, Consumer<CompoundTag>> steps) {
        return new NbtMigration(new MigrationVersions(name,
                JournalDataVersion.LEGACY_NBT_VERSION, JournalDataVersion.CURRENT_NBT_VERSION),
                JournalDataVersion.NBT_VERSION_TAG, steps);
    }

    private static void migrateTables(CompoundTag tag, Consumer<CompoundTag> migration, int version) {
        if (!tag.contains(TABLES_TAG, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag tables = tag.getCompound(TABLES_TAG);
        for (String tableId : tables.getAllKeys()) {
            CompoundTag table = tables.getCompound(tableId);
            migration.accept(table);
            table.putInt(JournalDataVersion.NBT_VERSION_TAG, version);
        }
    }

    private static void migrateTableLogHistoryV0ToV1(CompoundTag tag) {
        copyLootSource(tag, LEGACY_FIRST_UNLOCK_TRIGGER_TYPE_TAG, FIRST_UNLOCK_LOOT_SOURCE_TAG);
        tag.remove(LEGACY_FIRST_UNLOCK_TRIGGER_TYPE_TAG);
        if (!tag.contains(ENTRIES_TAG, Tag.TAG_LIST)) {
            return;
        }
        ListTag entries = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            migrateExcavationLogEntryV0ToV1(entry);
            entry.putInt(JournalDataVersion.NBT_VERSION_TAG,
                    JournalDataVersion.NBT_VERSION_LOOT_SOURCE);
        }
    }

    private static void migrateTableLogHistoryV1ToV2(CompoundTag tag) {
        ListTag entries = tag.contains(ENTRIES_TAG, Tag.TAG_LIST)
                ? tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND)
                : new ListTag();
        if (!tag.contains(LIFETIME_ENTRY_COUNT_TAG, Tag.TAG_LONG)) {
            tag.putLong(LIFETIME_ENTRY_COUNT_TAG, entries.size());
        }
        if (!tag.contains(RETENTION_LIMIT_TAG, Tag.TAG_INT)) {
            tag.putInt(RETENTION_LIMIT_TAG, 0);
        }
        for (int i = 0; i < entries.size(); i++) {
            entries.getCompound(i).putInt(JournalDataVersion.NBT_VERSION_TAG,
                    JournalDataVersion.NBT_VERSION_LOG_RETENTION);
        }
    }

    private static void migrateExcavationLogEntryV0ToV1(CompoundTag tag) {
        copyLootSource(tag, LEGACY_TRIGGER_TYPE_TAG, LOOT_SOURCE_TAG);
        tag.remove(LEGACY_TRIGGER_TYPE_TAG);
        if (!tag.contains(DIMENSION_ID_TAG, Tag.TAG_STRING)) {
            tag.putString(DIMENSION_ID_TAG, "minecraft:overworld");
        }
        migrateLegacyTimestamps(tag);
        migrateLegacyLoot(tag);
        tag.remove(LEGACY_GAME_TIME_TAG);
        tag.remove(LEGACY_DAY_TIME_TAG);
        tag.remove(LEGACY_ITEM_ID_TAG);
    }

    private static void copyLootSource(CompoundTag tag, String sourceKey, String targetKey) {
        if (tag.contains(targetKey, Tag.TAG_STRING) || !tag.contains(sourceKey, Tag.TAG_STRING)) {
            return;
        }
        LootSourceType source = parseLegacyLootSource(tag.getString(sourceKey));
        if (source != null) {
            tag.putString(targetKey, source.id().toString());
        }
    }

    // 旧 NBT 与旧同步包共享的来源类型名称映射
    public static LootSourceType parseLegacyLootSource(String name) {
        LootSourceType current = LootSourceType.fromId(name);
        if (current != null) {
            return current;
        }
        return switch (name) {
            case "archaeology", "brush", "reader", "spade" -> LootSourceType.ARCHAEOLOGY;
            case "loot_container", "container" -> LootSourceType.LOOT_CONTAINER;
            case "fishing" -> LootSourceType.FISHING;
            case "fossil_hunter" -> LootSourceType.FOSSIL_HUNTER;
            case "decorated_pot", "pot" -> LootSourceType.DECORATED_POT;
            default -> null;
        };
    }

    private static void migrateLegacyTimestamps(CompoundTag tag) {
        long gameTime = Math.max(0L, tag.getLong(LEGACY_GAME_TIME_TAG));
        long dayTime = tag.contains(LEGACY_DAY_TIME_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(LEGACY_DAY_TIME_TAG))
                : gameTime;
        copyLongIfMissing(tag, CREATED_GAME_TIME_TAG, gameTime);
        copyLongIfMissing(tag, CREATED_DAY_TIME_TAG, dayTime);
        copyLongIfMissing(tag, LAST_UPDATED_GAME_TIME_TAG, tag.getLong(CREATED_GAME_TIME_TAG));
        copyLongIfMissing(tag, LAST_UPDATED_DAY_TIME_TAG, tag.getLong(CREATED_DAY_TIME_TAG));
    }

    private static void migrateLegacyLoot(CompoundTag tag) {
        if (!tag.contains(LEGACY_ITEM_ID_TAG, Tag.TAG_STRING)) {
            return;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(tag.getString(LEGACY_ITEM_ID_TAG));
        if (itemId == null) {
            return;
        }
        CompoundTag loot = LootCounts.writeToNbt(Map.of(
                LootResultSignature.plain(itemId).toStoredKey(), 1));
        if (!tag.contains(EXPECTED_LOOT_TAG, Tag.TAG_COMPOUND)) {
            tag.put(EXPECTED_LOOT_TAG, loot.copy());
        }
        if (!tag.contains(ACTUAL_LOOT_TAG, Tag.TAG_COMPOUND)) {
            tag.put(ACTUAL_LOOT_TAG, loot);
        }
    }

    private static void copyLongIfMissing(CompoundTag tag, String key, long value) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            tag.putLong(key, Math.max(0L, value));
        }
    }
}
