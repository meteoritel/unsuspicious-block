package com.meteorite.unsuspiciousblock.enchantment.framework.trigger;

/** 附魔触发类型——定义何种游戏事件会触发附魔效果 */
public enum TriggerType {
    /** 玩家破坏方块 */
    BLOCK_BREAK,
    /** 玩家与实体交互（如剪羊毛） */
    ENTITY_INTERACT,
    /** 钓鱼收回 */
    FISHING_RETRIEVE,
    /** 刷拭可疑方块 */
    BRUSH_BLOCK
}
