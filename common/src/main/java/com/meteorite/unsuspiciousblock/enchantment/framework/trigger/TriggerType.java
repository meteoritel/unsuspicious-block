package com.meteorite.unsuspiciousblock.enchantment.framework.trigger;

/** 附魔触发类型——定义何种游戏事件会触发附魔效果 */
public enum TriggerType {
    /** 玩家破坏方块（fossil_hunter：骨块额外掉落） */
    BLOCK_BREAK,
    /** 剪羊毛（textile_recovery：额外掉线） */
    ENTITY_SHEAR,
    /** 刷拭可疑方块掉出物品，值变换（precision_excavation：翻倍物品） */
    BRUSH_ITEM_DROP
}
