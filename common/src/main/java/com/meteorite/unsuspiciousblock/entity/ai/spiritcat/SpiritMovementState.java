package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

/**
 * 猫国灵体通用移动状态，供服务端移动逻辑与客户端表现共享。
 */
public enum SpiritMovementState {
    HOVER,
    FLY,
    PHASE;

    // 从同步序号安全恢复，未知值按悬停处理。
    public static SpiritMovementState fromOrdinal(int ordinal) {
        SpiritMovementState[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : HOVER;
    }
}
