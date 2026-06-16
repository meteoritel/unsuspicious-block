package com.meteorite.unsuspiciousblock.cat;

/**
 * 猫之恩惠的累积行为类型——每种行为对应固定的恩惠增量，并共享统一的冷却时间。
 * 各行为通过 mixin 或平台事件触发，由 CatFavorManager 统一处理冷却与累积。
 */
public enum CatFavorAction {
    // 喂食猫（野猫或驯服的猫）
    FEED_CAT(5),
    // 成功驯服一只猫
    TAME_CAT(10),
    // 触发与猫一同入睡（原版行为：驯服的猫在主人睡觉时上床相伴）
    SLEEP_WITH_CAT(20),
    // 驯服的猫坐在床/箱子/燃烧的熔炉上持续 30s 不被打断
    SIT_ON_BLOCK(20),
    // 在村庄中击退袭击（获得「村庄英雄」效果时触发）
    REPEL_RAID(20);

    // 所有累积行为共享的冷却时间：5 分钟 = 6000 tick
    public static final long COOLDOWN_TICKS = 6000L;

    // 本行为触发时增加的恩惠值
    private final int favorDelta;

    CatFavorAction(int favorDelta) {
        this.favorDelta = favorDelta;
    }

    // 获取本行为的恩惠增量
    public int favorDelta() {
        return this.favorDelta;
    }
}
