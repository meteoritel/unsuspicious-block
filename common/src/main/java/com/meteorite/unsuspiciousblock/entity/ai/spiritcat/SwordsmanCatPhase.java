package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

/**
 * 剑士猫猫职业状态，用于服务端战斗流程和客户端职业动画。
 */
public enum SwordsmanCatPhase {
    MANIFEST,
    GUARD,
    CHASE,
    WINDUP,
    STRIKE,
    RETREAT,
    RETURN,
    DISSIPATE;

    // 从同步序号安全恢复，未知值按默认护卫处理。
    public static SwordsmanCatPhase fromOrdinal(int ordinal) {
        SwordsmanCatPhase[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : GUARD;
    }
}
