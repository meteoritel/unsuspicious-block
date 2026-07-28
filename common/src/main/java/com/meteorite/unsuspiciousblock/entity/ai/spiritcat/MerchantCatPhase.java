package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

/**
 * 猫猫商人职业状态，用于村庄活动、交易锁定与客户端表现。
 */
public enum MerchantCatPhase {
    MANIFEST,
    OBSERVE,
    ROAM,
    ATTEND,
    TRADING,
    TRADE_REACTION,
    RETURN,
    FAREWELL,
    DISSIPATE;

    // 从同步序号安全恢复，未知值按观察状态处理。
    public static MerchantCatPhase fromOrdinal(int ordinal) {
        MerchantCatPhase[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : OBSERVE;
    }
}
