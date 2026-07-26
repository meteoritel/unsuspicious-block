package com.meteorite.unsuspiciousblock.cat;

/**
 * 猫之恩惠能力枚举——集中管理各项被动能力的解锁阈值与本地化键。
 * <p>
 * 阈值用于服务端能力判定（{@link CatPassiveAbilities}），nameKey/descKey 供 tooltip 与 HUD 显示。
 * 枚举声明顺序即 tooltip 展示顺序（由低阈值到高阈值）。
 */
public enum CatFavorAbility {
    CAT_EYE(0, "cat_eye"),
    DETERRENCE(20, "deterrence"),
    LIGHT_STEP(40, "light_step"),
    SOFT_PAWS(60, "soft_paws"),
    ANCIENT_GIFT(80, "ancient_gift"),
    NINE_LIVES(100, "nine_lives");

    private static final String KEY_PREFIX = "item.unsuspiciousblock.hand_of_cat.ability.";

    private final int threshold;
    private final String nameKey;
    private final String descKey;

    CatFavorAbility(int threshold, String id) {
        this.threshold = threshold;
        this.nameKey = KEY_PREFIX + id;
        this.descKey = KEY_PREFIX + id + ".desc";
    }

    // 该能力解锁所需的恩惠值
    public int threshold() {
        return this.threshold;
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
        return favor >= this.threshold;
    }
}
