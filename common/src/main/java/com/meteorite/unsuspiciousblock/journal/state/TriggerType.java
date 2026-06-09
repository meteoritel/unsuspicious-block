package com.meteorite.unsuspiciousblock.journal.state;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 日志条目的触发来源类型。
 */
public enum TriggerType {
    UNKNOWN("unknown", "screen.unsuspiciousblock.archaeology_journal.log_trigger_type.unknown"),
    BRUSH("brush", "screen.unsuspiciousblock.archaeology_journal.log_trigger_type.brush"),
    READER("reader", "screen.unsuspiciousblock.archaeology_journal.log_trigger_type.reader"),
    SPADE("spade", "screen.unsuspiciousblock.archaeology_journal.log_trigger_type.spade"),
    CONTAINER("container", "screen.unsuspiciousblock.archaeology_journal.log_trigger_type.container");

    private final String serializedName;
    private final String translationKey;

    TriggerType(String serializedName, String translationKey) {
        this.serializedName = serializedName;
        this.translationKey = translationKey;
    }

    // 返回枚举的序列化名称
    public String serializedName() {
        return this.serializedName;
    }

    // 返回本地化显示名
    public Component displayName() {
        return Component.translatable(this.translationKey);
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