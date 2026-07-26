package com.meteorite.unsuspiciousblock.cat;

/**
 * 猫族羁绊行为类型——统一管理正向行为、负向行为与独立冷却。
 * 关系建立后正向行为不要求携带猫之手；负向行为无冷却。
 * 各行为通过 mixin 或平台事件触发，由 CatFavorManager 统一处理。
 */
public enum CatFavorAction {
    // ========== 正向行为（关系建立后生效，独立冷却） ==========
    // 喂食猫（野猫或驯服的猫）
    FEED_CAT(5, 12000L, false),
    // 成功驯服一只猫
    TAME_CAT(10, 12000L, false),
    // 触发与猫一同入睡（原版行为：驯服的猫在主人睡觉时上床相伴）
    SLEEP_WITH_CAT(20, 6000L, false),
    // 驯服的猫坐在床/箱子/燃烧的熔炉上持续 30s 不被打断
    SIT_ON_BLOCK(10, 24000L, false),
    // 在村庄中击退袭击（获得「村庄英雄」效果时触发）
    REPEL_RAID(20, 12000L, false),

    // ========== 惩罚行为（不要求持有猫之手，无冷却） ==========
    // 击打猫（每次玩家对猫造成伤害时触发）
    HIT_CAT(-5, 0L, true),
    // 玩家所属的驯服猫死亡
    OWN_CAT_DEATH(-10, 0L, true),
    // 玩家杀死猫（任意猫，含野猫与他人驯服猫）
    KILL_CAT(-50, 0L, true);

    // 本行为触发时的恩惠增量（惩罚为负）
    private final int favorDelta;
    // 本行为独立的冷却时间（tick）；惩罚行为为 0（无冷却）
    private final long cooldownTicks;
    // 是否为惩罚行为（惩罚跳过持有物校验与冷却）
    private final boolean penalty;

    CatFavorAction(int favorDelta, long cooldownTicks, boolean penalty) {
        this.favorDelta = favorDelta;
        this.cooldownTicks = cooldownTicks;
        this.penalty = penalty;
    }

    // 获取本行为的恩惠增量
    public int favorDelta() {
        return this.favorDelta;
    }

    // 获取本行为独立的冷却时间
    public long cooldownTicks() {
        return this.cooldownTicks;
    }

    // 是否为惩罚行为
    public boolean isPenalty() {
        return this.penalty;
    }
}
