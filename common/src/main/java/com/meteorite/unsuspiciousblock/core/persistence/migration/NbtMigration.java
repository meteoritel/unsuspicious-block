package com.meteorite.unsuspiciousblock.core.persistence.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NBT 连续迁移执行器。旧字段转换在副本中完成，全部成功后才提交到输入 tag。
 * 当前格式直接通过，保持现有编码与未知字段。
 */
public final class NbtMigration {
    private final MigrationVersions versions;
    private final String versionKey;
    private final Map<Integer, Consumer<CompoundTag>> steps;

    /**
     * 注册从最早支持版本到当前版本的完整迁移链；Map 的键是来源版本，目标恒为下一版本。
     * 缺少版本标记时使用最早支持版本；当前版本等于最早版本时可用空 Map，保持无版本格式。
     */
    public NbtMigration(MigrationVersions versions, String versionKey,
                        Map<Integer, Consumer<CompoundTag>> steps) {
        this.versions = versions;
        this.versionKey = versionKey;
        this.steps = Map.copyOf(steps);
        for (int from : this.steps.keySet()) {
            versions.validateStep(from, from + 1);
        }
        for (int from = versions.oldest(); from < versions.current(); from++) {
            if (!this.steps.containsKey(from)) {
                throw new IllegalArgumentException(versions.name() + " 缺少 v" + from
                        + " -> v" + (from + 1) + " 迁移步骤");
            }
        }
    }

    /**
     * 原地提交成功的迁移；失败时输入及其版本标记保持原样。
     * 转换函数只修改收到的副本，版本标记由本执行器推进。
     *
     * @throws IllegalArgumentException 版本字段类型错误或版本不受支持
     */
    public void migrate(CompoundTag source) {
        if (source.contains(versionKey) && !source.contains(versionKey, Tag.TAG_INT)) {
            throw new IllegalArgumentException(versions.name() + " 版本字段类型错误: " + versionKey);
        }
        int version = source.contains(versionKey, Tag.TAG_INT)
                ? source.getInt(versionKey) : versions.oldest();
        versions.validate(version);
        if (version == versions.current()) {
            return;
        }

        CompoundTag migrated = source.copy();
        while (version < versions.current()) {
            steps.get(version).accept(migrated);
            migrated.putInt(versionKey, ++version);
        }
        // merge 不能移除旧键，须在整个转换成功后一次替换根内容。
        new HashSet<>(source.getAllKeys()).forEach(source::remove);
        source.merge(migrated);
    }
}
