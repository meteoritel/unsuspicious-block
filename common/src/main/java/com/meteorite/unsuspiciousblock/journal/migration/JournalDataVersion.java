package com.meteorite.unsuspiciousblock.journal.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 考古日志数据版本的统一定义。
 * <p>
 * NBT schema 版本与磁盘存储布局版本是两个独立维度：前者描述字段结构，
 * 后者描述文件组织方式。升级任一维度时都必须在本类记录版本历史。
 */
public final class JournalDataVersion {
    public static final String NBT_VERSION_TAG = "data_version";
    public static final int LEGACY_NBT_VERSION = 0;
    public static final int NBT_VERSION_LOOT_SOURCE = 1;
    public static final int CURRENT_NBT_VERSION = NBT_VERSION_LOOT_SOURCE;

    public static final String STORAGE_VERSION_TAG = "storage_version";
    public static final int STORAGE_VERSION_SINGLE_FILE = 1;
    public static final int STORAGE_VERSION_SHARDED = 2;
    public static final int LEGACY_STORAGE_VERSION = STORAGE_VERSION_SINGLE_FILE;
    public static final int CURRENT_STORAGE_VERSION = STORAGE_VERSION_SHARDED;

    private JournalDataVersion() {
    }

    // 无版本字段的数据视为最早的 v0 格式
    public static int readNbtVersion(CompoundTag tag) {
        return tag.contains(NBT_VERSION_TAG, Tag.TAG_INT)
                ? tag.getInt(NBT_VERSION_TAG)
                : LEGACY_NBT_VERSION;
    }

    // v1 存储没有 manifest；缺少版本字段时按单文件布局处理
    public static int readStorageVersion(CompoundTag tag) {
        return tag.contains(STORAGE_VERSION_TAG, Tag.TAG_INT)
                ? tag.getInt(STORAGE_VERSION_TAG)
                : LEGACY_STORAGE_VERSION;
    }
}
