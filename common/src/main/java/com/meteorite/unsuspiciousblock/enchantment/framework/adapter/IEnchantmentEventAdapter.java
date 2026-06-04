package com.meteorite.unsuspiciousblock.enchantment.framework.adapter;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;

/** 平台事件适配器接口——各平台模块实现此接口，将平台特定事件转换为统一的 {@link com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext} 并路由到 EnchantmentManager */
public interface IEnchantmentEventAdapter {
    /** 注册平台事件监听，内部调用 {@link EnchantmentManager#dispatch} 转发事件 */
    void registerListeners();
}
