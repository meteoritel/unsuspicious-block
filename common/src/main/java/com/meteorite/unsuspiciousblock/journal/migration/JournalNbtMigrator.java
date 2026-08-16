package com.meteorite.unsuspiciousblock.journal.migration;

import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * 考古日志 NBT schema 迁移器。
 * <p>
 * 所有旧字段识别和逐版本转换只允许定义在本类。状态类的反序列化方法只读取
 * 当前格式，避免未来升级时在多个 {@code fromTag()} 中重复维护兼容分支。
 */
public final class JournalNbtMigrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(JournalNbtMigrator.class);

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

    private JournalNbtMigrator() {
    }

    // 迁移玩家考古目录进度；v0→v1 没有字段变化，只补齐版本标记
    public static void migrateJournalState(CompoundTag tag) {
        migrateToCurrent(tag, "ArchaeologyJournalState",
                ignored -> JournalDataVersion.NBT_VERSION_LOOT_SOURCE);
    }

    // 迁移完整日志状态及其所有表、条目
    public static void migrateJournalLogState(CompoundTag tag) {
        migrateToCurrent(tag, "ArchaeologyJournalLogState", version -> switch (version) {
            case 0 -> {
                if (tag.contains(TABLES_TAG, Tag.TAG_COMPOUND)) {
                    CompoundTag tables = tag.getCompound(TABLES_TAG);
                    for (String tableId : tables.getAllKeys()) {
                        CompoundTag table = tables.getCompound(tableId);
                        migrateTableLogHistoryV0ToV1(table);
                        table.putInt(JournalDataVersion.NBT_VERSION_TAG,
                                JournalDataVersion.NBT_VERSION_LOOT_SOURCE);
                    }
                }
                yield JournalDataVersion.NBT_VERSION_LOOT_SOURCE;
            }
            default -> missingMigration("ArchaeologyJournalLogState", version);
        });
    }

    // 迁移单张战利品表日志及其所有条目
    public static void migrateTableLogHistory(CompoundTag tag) {
        migrateToCurrent(tag, "TableLogHistory", version -> switch (version) {
            case 0 -> {
                migrateTableLogHistoryV0ToV1(tag);
                yield JournalDataVersion.NBT_VERSION_LOOT_SOURCE;
            }
            default -> missingMigration("TableLogHistory", version);
        });
    }

    // 迁移单条日志；条目从 v1 起也独立携带版本，便于未来单独演进
    public static void migrateExcavationLogEntry(CompoundTag tag) {
        migrateToCurrent(tag, "ExcavationLogEntry", version -> switch (version) {
            case 0 -> {
                migrateExcavationLogEntryV0ToV1(tag);
                yield JournalDataVersion.NBT_VERSION_LOOT_SOURCE;
            }
            default -> missingMigration("ExcavationLogEntry", version);
        });
    }

    private static void migrateToCurrent(CompoundTag tag, String dataClass, IntUnaryOperator migrationStep) {
        int version = JournalDataVersion.readNbtVersion(tag);
        if (version < JournalDataVersion.LEGACY_NBT_VERSION) {
            throw new IllegalArgumentException(dataClass + " 数据版本无效: " + version);
        }
        if (version > JournalDataVersion.CURRENT_NBT_VERSION) {
            throw new IllegalArgumentException(dataClass + " 数据版本过新: " + version);
        }

        int originalVersion = version;
        while (version < JournalDataVersion.CURRENT_NBT_VERSION) {
            int nextVersion = migrationStep.applyAsInt(version);
            if (nextVersion != version + 1) {
                throw new IllegalStateException(dataClass + " 迁移链不连续: " + version + " -> " + nextVersion);
            }
            version = nextVersion;
            tag.putInt(JournalDataVersion.NBT_VERSION_TAG, version);
        }
        if (originalVersion != version) {
            LOGGER.debug("迁移 {} 数据: {} -> {}", dataClass, originalVersion, version);
        }
    }

    private static int missingMigration(String dataClass, int version) {
        throw new IllegalStateException(dataClass + " 缺少 v" + version + " -> v" + (version + 1) + " 迁移步骤");
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
