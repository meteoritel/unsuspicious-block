package com.meteorite.unsuspiciousblock.journal.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NBT 数据迁移器——将旧版本数据格式迁移到当前版本。
 * <p>
 * 读取数据时，若 NBT 中无 {@link NbtDataVersion#TAG} 字段，
 * 则视为版本 0（旧格式），由本类执行必要的迁移。
 * <p>
 * 当前迁移内容（版本 0 → 1）：
 * <ul>
 *   <li>ExcavationLogEntry: trigger_type → loot_source, brush→archaeology, container→loot_container</li>
 *   <li>ExcavationLogEntry: 缺失 dimension_id 默认 minecraft:overworld（由 ExcavationContext compact constructor 处理）</li>
 *   <li>TableLogHistory: first_unlock_trigger_type → first_unlock_loot_source</li>
 * </ul>
 * <p>
 * 注意：这些迁移逻辑已经内嵌在各自的 {@code fromTag()} 方法中，
 * 通过条件读取（先读新字段、缺失时回退读旧字段）实现了零停机兼容。
 * 本类作为集中入口，未来若需要更复杂的迁移（如字段重命名、结构重组），
 * 可在此处统一管理。
 *
 * @deprecated 临时版本迁移兼容层，计划在 1.5.0 移除
 */
@Deprecated(since = "1.5.0")
public final class NbtDataMigrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(NbtDataMigrator.class);

    private NbtDataMigrator() {
    }

    /**
     * 检测 NBT 数据版本并执行必要的迁移。
     * <p>
     * 对于版本 0 → 1 的迁移，所有兼容逻辑已内嵌在各 {@code fromTag()} 中，
     * 因此此方法目前仅记录版本信息日志，不做额外变换。
     * 未来版本若需要更复杂的迁移（如字段重命名、嵌套结构变更），
     * 可在此方法中添加预处理步骤。
     *
     * @param tag        顶层 NBT 数据（会被原地修改）
     * @param dataClass  数据类名，仅用于日志
     * @return 迁移后的数据版本号
     */
    public static int migrateIfNeeded(CompoundTag tag, String dataClass) {
        int version = tag.contains(NbtDataVersion.TAG, Tag.TAG_INT)
                ? tag.getInt(NbtDataVersion.TAG)
                : NbtDataVersion.LEGACY;

        if (version < NbtDataVersion.CURRENT) {
            LOGGER.debug("Migrating {} data from version {} to {}", dataClass, version, NbtDataVersion.CURRENT);
            // 版本 0 → 1 的迁移由各 fromTag() 内的兼容读取逻辑完成
            // 未来若需要预处理（如批量重命名字段），可在此处添加
        }

        return version;
    }

    /**
     * 对 TableLogHistory 级别的 NBT 执行迁移。
     * <p>
     * 主要处理旧字段 {@code first_unlock_trigger_type} → {@code first_unlock_loot_source}。
     * 实际的兼容读取已内嵌在 {@link ArchaeologyJournalLogState.TableLogHistory#fromTag(CompoundTag)} 中，
     * 此方法供未来扩展使用。
     *
     * @param tableTag 单个表的 NBT 数据
     */
    public static void migrateTableLogHistory(CompoundTag tableTag) {
        // 版本 0 → 1: first_unlock_trigger_type → first_unlock_loot_source
        // 已在 TableLogHistory.fromTag() 内处理，此处预留扩展
    }

    /**
     * 对 ExcavationLogEntry 级别的 NBT 执行迁移。
     * <p>
     * 主要处理：
     * <ul>
     *   <li>{@code trigger_type} → {@code loot_source}（值映射：brush→archaeology, container→loot_container）</li>
     *   <li>移除 {@code source_block_id}（无替代字段）</li>
     *   <li>缺失 {@code dimension_id} 时由 ExcavationContext compact constructor 默认为 minecraft:overworld</li>
     * </ul>
     * 实际的兼容读取已内嵌在 {@link ExcavationLogEntry#fromTag(CompoundTag)} 中，
     * 此方法供未来扩展使用。
     *
     * @param entryTag 单条日志的 NBT 数据
     */
    public static void migrateEntryTag(CompoundTag entryTag) {
        // 版本 0 → 1: trigger_type → loot_source, source_block_id 移除
        // 已在 ExcavationLogEntry.fromTag() 内处理，此处预留扩展
    }
}
