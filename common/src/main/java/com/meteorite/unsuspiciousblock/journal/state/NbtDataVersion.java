package com.meteorite.unsuspiciousblock.journal.state;

/**
 * NBT 数据版本常量。
 * <p>
 * 每次数据格式发生不兼容变更时递增版本号，
 * 旧版本数据在读取时由 {@link NbtDataMigrator} 执行迁移。
 * <p>
 * 版本历史：
 * <ul>
 *   <li>版本 0：无 version 字段（旧格式，trigger_type/source_block_id 等旧字段）</li>
 *   <li>版本 1：引入 LootSourceType（替代 TriggerType）、dimensionId、
 *               first_unlock_loot_source（替代 first_unlock_trigger_type）</li>
 * </ul>
 */
public final class NbtDataVersion {
    // 当前数据格式版本
    public static final int CURRENT = 1;

    // 旧数据（无 version 字段）的隐含版本
    public static final int LEGACY = 0;

    // NBT key
    public static final String TAG = "data_version";

    private NbtDataVersion() {
    }
}