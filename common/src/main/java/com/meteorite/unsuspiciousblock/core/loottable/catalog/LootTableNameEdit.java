package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import net.minecraft.resources.ResourceLocation;

/** 单项补充表名编辑。空值表示该语言没有服务端补充。 */
public record LootTableNameEdit(ResourceLocation tableId, String language, String value, String expected, String legacyKey) {
    public LootTableNameEdit(ResourceLocation tableId, String language, String value, String expected) {
        this(tableId, language, value, expected, "");
    }

    public static final int MAX_LANGUAGE_LENGTH = 16;
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_WIRE_NAME_LENGTH = MAX_NAME_LENGTH * 2;

    /** 语言文件的文件名、导入和编辑使用同一语法。 */
    public static boolean isLanguage(String language) {
        return language.matches("[a-z0-9_-]{2," + MAX_LANGUAGE_LENGTH + "}");
    }

    /** UI Kit 按 Unicode 码点限制输入，提交与磁盘采用相同口径。 */
    public static boolean isName(String value) {
        return value.codePointCount(0, value.length()) <= MAX_NAME_LENGTH
                && value.codePoints().noneMatch(code -> Character.isISOControl(code)
                        || code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE);
    }

    /** 每项结果携带服务端现值用于比较或恢复草稿。 */
    public record Result(ResourceLocation tableId, String language, Status status, String current) {}

    /** 一次编辑的逐项终态。 */
    public enum Status { SAVED, UNCHANGED, CONFLICT, INVALID, WRITE_FAILED, READ_ONLY }
}
