package com.meteorite.unsuspiciousblock.cat;

/**
 * 猫族羁绊阶段——将 0-100 的连续羁绊值映射为稳定的关系阶段与 HUD 颜色。
 */
public enum CatBondStage {
    ACQUAINTED(0, 19, "acquainted", 0xFFB0BEC5),
    CLOSE(20, 39, "close", 0xFF81C784),
    TRUSTED(40, 59, "trusted", 0xFF4DD0E1),
    FAVORED(60, 79, "favored", 0xFFFFD54F),
    HONORED_GUEST(80, 99, "honored_guest", 0xFFFF8A80),
    BEST_FRIEND(100, 100, "best_friend", 0xFFC5A3FF);

    private static final String KEY_PREFIX = "cat.unsuspiciousblock.bond_stage.";

    private final int minimumBond;
    private final int maximumBond;
    private final String translationKey;
    private final int hudColor;

    CatBondStage(int minimumBond, int maximumBond, String id, int hudColor) {
        this.minimumBond = minimumBond;
        this.maximumBond = maximumBond;
        this.translationKey = KEY_PREFIX + id;
        this.hudColor = hudColor;
    }

    // 根据羁绊值返回当前阶段。
    public static CatBondStage fromBond(int bond) {
        int clamped = Math.max(0, Math.min(100, bond));
        for (CatBondStage stage : values()) {
            if (clamped >= stage.minimumBond && clamped <= stage.maximumBond) {
                return stage;
            }
        }
        return ACQUAINTED;
    }

    public int minimumBond() {
        return this.minimumBond;
    }

    public int maximumBond() {
        return this.maximumBond;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public int hudColor() {
        return this.hudColor;
    }
}
