package com.meteorite.unsuspiciousblock.core.persistence.migration;

/**
 * 一个持久化格式的独立版本轴；版本仅随字段、编码或文件组织变化推进。
 *
 * @param name 用于定位存档问题的格式名称
 * @param oldest 最早支持的版本；缺少版本标记的数据按该版本处理
 * @param current 当前写出的版本
 */
public record MigrationVersions(String name, int oldest, int current) {
    /** 创建版本范围；当前版本须不低于最早支持版本。 */
    public MigrationVersions {
        if (oldest < 0 || current < oldest) {
            throw new IllegalArgumentException(name + " 迁移版本范围无效: " + oldest + " -> " + current);
        }
    }

    /**
     * 校验外部数据版本；未知旧版本与未来版本均在修改数据前拒绝。
     *
     * @throws IllegalArgumentException 版本不在支持范围内
     */
    public void validate(int version) {
        if (version < oldest) {
            throw new IllegalArgumentException(name + " 数据版本无效: " + version);
        }
        if (version > current) {
            throw new IllegalArgumentException(name + " 数据版本过新: " + version);
        }
    }

    /** 校验一步迁移的起止版本，拒绝跳步和超出当前版本的目标。 */
    public void validateStep(int from, int to) {
        validate(from);
        validate(to);
        if (to != from + 1) {
            throw new IllegalArgumentException(name + " 迁移必须连续: " + from + " -> " + to);
        }
    }
}
