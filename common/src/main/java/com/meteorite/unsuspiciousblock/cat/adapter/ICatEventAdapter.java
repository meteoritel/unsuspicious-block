package com.meteorite.unsuspiciousblock.cat.adapter;

/**
 * 猫之手平台事件适配器——由各平台实现，注册玩家死亡、杀猫、登录等事件，
 * 抹平平台差异后路由到 CatFavorManager。仿照 IEnchantmentEventAdapter 设计。
 */
public interface ICatEventAdapter {
    // 注册平台事件监听
    void registerListeners();
}
