package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonObject;

/**
 * 战利品表 JSON 解析公共工具方法——消除 {@link LootConditionHandlers}、
 * {@link LootFunctionHandlers} 与 {@link LootTableJsonParser} 中重复的
 * 类型名规范化与 JSON 字段读取逻辑。
 */
public final class LootParseUtil {

    private LootParseUtil() {
    }

    /**
     * 规范化战利品表条件/函数的类型名，去除命名空间前缀。
     * 例：{@code "minecraft:random_chance"} → {@code "random_chance"}
     */
    public static String normalizeType(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        int colonIndex = type.indexOf(':');
        return colonIndex >= 0 ? type.substring(colonIndex + 1) : type;
    }

    /**
     * 从 JSON 对象中读取字符串字段，缺失时返回 fallback。
     */
    public static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }
}