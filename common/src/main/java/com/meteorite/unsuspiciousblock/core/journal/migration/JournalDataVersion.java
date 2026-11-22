package com.meteorite.unsuspiciousblock.core.journal.migration;

import com.meteorite.unsuspiciousblock.core.persistence.migration.MigrationVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 考古日志数据版本的统一定义。
 * <p>
 * 各 NBT schema 与磁盘存储布局分别演进；NBT 格式各自记录字段结构版本，
 * 布局版本记录文件组织方式。
 */
public final class JournalDataVersion {
    public static final String NBT_VERSION_TAG = "data_version";
    public static final int LEGACY_NBT_VERSION = 0;
    public static final int NBT_VERSION_LOOT_SOURCE = 1;
    public static final int NBT_VERSION_LOG_RETENTION = 2;
    public static final int JOURNAL_STATE_NBT_CURRENT = 2;
    public static final int JOURNAL_LOG_STATE_NBT_CURRENT = 2;
    public static final int TABLE_LOG_HISTORY_NBT_CURRENT = 2;
    public static final int EXCAVATION_LOG_ENTRY_NBT_CURRENT = 2;

    public static final MigrationVersions JOURNAL_STATE_NBT = new MigrationVersions(
            "ArchaeologyJournalState", LEGACY_NBT_VERSION, JOURNAL_STATE_NBT_CURRENT);
    public static final MigrationVersions JOURNAL_LOG_STATE_NBT = new MigrationVersions(
            "ArchaeologyJournalLogState", LEGACY_NBT_VERSION, JOURNAL_LOG_STATE_NBT_CURRENT);
    public static final MigrationVersions TABLE_LOG_HISTORY_NBT = new MigrationVersions(
            "TableLogHistory", LEGACY_NBT_VERSION, TABLE_LOG_HISTORY_NBT_CURRENT);
    public static final MigrationVersions EXCAVATION_LOG_ENTRY_NBT = new MigrationVersions(
            "ExcavationLogEntry", LEGACY_NBT_VERSION, EXCAVATION_LOG_ENTRY_NBT_CURRENT);

    public static final String STORAGE_VERSION_TAG = "storage_version";
    public static final int STORAGE_VERSION_SINGLE_FILE = 1;
    public static final int STORAGE_VERSION_SHARDED = 2;
    public static final int STORAGE_VERSION_COMMITTED_INDEX = 3;
    public static final int LEGACY_STORAGE_VERSION = STORAGE_VERSION_SINGLE_FILE;
    public static final MigrationVersions STORAGE_LAYOUT = new MigrationVersions(
            "考古日志存储", LEGACY_STORAGE_VERSION, STORAGE_VERSION_COMMITTED_INDEX);

    private JournalDataVersion() {
    }

    // 无版本字段的数据视为最早的 v0 格式
    public static int readNbtVersion(CompoundTag tag, MigrationVersions format) {
        return tag.contains(NBT_VERSION_TAG, Tag.TAG_INT)
                ? tag.getInt(NBT_VERSION_TAG)
                : format.oldest();
    }

    // v1 存储没有 manifest；缺少版本字段时按单文件布局处理
    public static int readStorageVersion(CompoundTag tag) {
        return tag.contains(STORAGE_VERSION_TAG, Tag.TAG_INT)
                ? tag.getInt(STORAGE_VERSION_TAG)
                : LEGACY_STORAGE_VERSION;
    }
}
