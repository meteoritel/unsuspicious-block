package com.meteorite.unsuspiciousblock.core.cat;

/**
 * 猫之恩惠能力枚举——集中管理各项被动能力的解锁阈值与本地化键。
 * <p>
 * 阈值用于服务端能力判定（{@link CatPassiveAbilities}），nameKey/descKey 供 tooltip 与 HUD 显示。
 * 枚举声明顺序即 tooltip 展示顺序（由低阈值到高阈值）。
 */
public enum CatFavorAbility {
    CAT_EYE(CatBondStage.ACQUAINTED, "cat_eye"),
    DETERRENCE(CatBondStage.CLOSE, "deterrence"),
    LIGHT_STEP(CatBondStage.TRUSTED, "light_step"),
    SOFT_PAWS(CatBondStage.FAVORED, "soft_paws"),
    ANCIENT_GIFT(CatBondStage.HONORED_GUEST, "ancient_gift"),
    NINE_LIVES(CatBondStage.BEST_FRIEND, "nine_lives");

    private static final String KEY_PREFIX = "item.unsuspiciousblock.hand_of_cat.ability.";

    private final CatBondStage unlocksAt;
    private final String nameKey;
    private final String descKey;

    CatFavorAbility(CatBondStage unlocksAt, String id) {
        this.unlocksAt = unlocksAt;
        this.nameKey = KEY_PREFIX + id;
        this.descKey = KEY_PREFIX + id + ".desc";
    }

    /** 能力的解锁阶段，也是 toast 与 tooltip 的共同刻度。 */
    public CatBondStage unlocksAt() {
        return this.unlocksAt;
    }

    // 该能力解锁所需的恩惠值
    public int threshold() {
        return this.unlocksAt.minimumBond();
    }

    // 能力名称的本地化键（用于 tooltip / HUD 标题）
    public String nameKey() {
        return this.nameKey;
    }

    // 能力描述的本地化键（用于 tooltip 详尽模式）
    public String descKey() {
        return this.descKey;
    }

    // 当前恩惠值是否已解锁该能力
    public boolean isUnlockedAt(int favor) {
        return favor >= threshold();
    }
}
