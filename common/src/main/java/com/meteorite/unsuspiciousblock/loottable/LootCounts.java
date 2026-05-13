package com.meteorite.unsuspiciousblock.loottable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 战利品计数 Map 的规范化与合并工具方法。
 * <p>
 * 消除散落在多个类中的"遍历 Map → 过滤空/无效值 → 验证签名 → 合并计数"重复模式。
 */
public final class LootCounts {
    private LootCounts() {
    }

    /**
     * 过滤空键、空值、零/负计数以及无效签名键，并合并重复键的计数。
     * 返回不可变的规范化 Map；若输入为空或全部无效，返回空 Map。
     */
    public static Map<String, Integer> normalize(@Nullable Map<String, Integer> lootCounts) {
        if (lootCounts == null || lootCounts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : lootCounts.entrySet()) {
            String signatureKey = entry.getKey();
            Integer count = entry.getValue();
            if (signatureKey == null || signatureKey.isBlank() || count == null || count <= 0) {
                continue;
            }
            if (LootResultSignature.fromStoredKey(signatureKey) == null) {
                continue;
            }
            normalized.merge(signatureKey, count, Integer::sum);
        }
        if (normalized.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * 将 source 中的有效条目合并到 target（使用 Integer::sum）。
     * 不修改 source；直接修改 target。
     */
    public static void mergeInto(Map<String, Integer> target, @Nullable Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String signatureKey = entry.getKey();
            Integer count = entry.getValue();
            if (signatureKey == null || signatureKey.isBlank() || count == null || count <= 0) {
                continue;
            }
            target.merge(signatureKey, count, Integer::sum);
        }
    }

    /**
     * 从父 NBT CompoundTag 的指定键中读取战利品计数 Map。
     * 只保留 count > 0 的条目，并返回不可变 Map。
     */
    public static Map<String, Integer> readFromNbt(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return Map.of();
        }
        return readFromNbt(tag.getCompound(key));
    }

    /**
     * 从战利品子 CompoundTag 中读取战利品计数 Map。
     * 只保留 count > 0 的条目，并返回不可变 Map。
     */
    public static Map<String, Integer> readFromNbt(CompoundTag lootTag) {
        if (lootTag.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> lootMap = new LinkedHashMap<>();
        for (String tagKey : lootTag.getAllKeys()) {
            int count = lootTag.getInt(tagKey);
            if (count > 0) {
                lootMap.put(tagKey, count);
            }
        }
        if (lootMap.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(lootMap);
    }

    /**
     * 将战利品计数 Map 写入 NBT CompoundTag。
     * 只写入 count > 0 的条目。
     */
    public static CompoundTag writeToNbt(Map<String, Integer> lootMap) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : lootMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                tag.putInt(entry.getKey(), entry.getValue());
            }
        }
        return tag;
    }
}