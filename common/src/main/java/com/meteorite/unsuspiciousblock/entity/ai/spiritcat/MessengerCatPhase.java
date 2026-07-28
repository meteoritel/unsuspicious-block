package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

/**
 * 幽灵猫生命周期阶段
 * <p>
 * 阶段流转：MANIFEST → APPROACH/RELOCATE → GREET → DELIVER → FAREWELL → DISSIPATE
 * <p>
 * 任一阶段中目标失效或总寿命耗尽，直接跳转 DISSIPATE（不赠礼）。
 */
public enum MessengerCatPhase {
    // 显现：原地浮现，alpha 渐入，禁用移动
    MANIFEST,
    // 接近：绕障并沿弧线靠近收礼玩家
    APPROACH,
    // 重新定位：淡出后在玩家附近显现，单次配送最多执行两次
    RELOCATE,
    // 致意：到达后停驻，抬头看玩家，播放粒子与声效
    GREET,
    // 赠礼：掷出战利品表物品
    DELIVER,
    // 告别：投放后短暂观察收礼玩家
    FAREWELL,
    // 消散：上升 + alpha 渐出 + 粒子爆散，随后 discard
    DISSIPATE
}
