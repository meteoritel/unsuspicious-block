package com.meteorite.unsuspiciousblock.cat.merchant;

/**
 * 猫猫商人交易池——控制每次生成时从不同层级抽取的交易数量。
 */
public enum MerchantCatTradePool {
    EARNING,
    REGULAR,
    RARE;

    public static MerchantCatTradePool parse(String value) {
        return switch (value) {
            case "earning" -> EARNING;
            case "regular" -> REGULAR;
            case "rare" -> RARE;
            default -> throw new IllegalArgumentException("未知猫猫商人交易池: " + value);
        };
    }
}
