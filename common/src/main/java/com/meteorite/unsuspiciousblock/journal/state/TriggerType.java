package com.meteorite.unsuspiciousblock.journal.state;

import org.jetbrains.annotations.Nullable;

/**
 * 日志条目的触发来源类型。
 */
public enum TriggerType {
    UNKNOWN("unknown"),
    BRUSH("brush"),
    READER("reader"),
    SPADE("spade"),
    CONTAINER("container");

    private final String serializedName;

    TriggerType(String serializedName) {
        this.serializedName = serializedName;
    }

    // 返回枚举的序列化名称
    public String serializedName() {
        return this.serializedName;
    }

    // 根据序列化名称反序列化枚举值
    @Nullable
    public static TriggerType fromSerializedName(String name) {
        for (TriggerType value : values()) {
            if (value.serializedName.equals(name)) {
                return value;
            }
        }
        return null;
    }
}