package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

/**
 * 猫国灵体运行模式，区分模型预览、正常职责和调试职责实体。
 */
public enum SpiritRunMode {
    PREVIEW,
    DUTY,
    DEBUG_DUTY;

    // 从持久化序号安全恢复，未知值退回预览模式。
    public static SpiritRunMode fromOrdinal(int ordinal) {
        SpiritRunMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PREVIEW;
    }

    // 只有正常职责和调试职责实体允许运行职业 AI。
    public boolean runsDutyAi() {
        return this != PREVIEW;
    }
}
