package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentEffects;
import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.reveal.EnchantmentRevealConditions;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingBootstrap;
import com.meteorite.unsuspiciousblock.loottable.condition.ModLootConditions;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.VanillaAchievementHelper;

/** Common 模块统一入口，由各平台模块调用 */
public class UnsuspiciousBlockCommon {
    public static void init() {
        Constants.LOG.info("UnsuspiciousBlockCommon init on {}", Services.PLATFORM.getPlatformName());
        AchievementManager.init(new VanillaAchievementHelper());
        EnchantmentManager.init(Services.ENCHANTMENT);
        EnchantmentEffects.registerAll();
        ModLootConditions.registerAnalysisHandlers();
        // 注册附魔揭示内建条件（服务端评估，决定是否下发完整候选列表）
        EnchantmentRevealConditions.register();
        // 注册战利品发现事件的内建订阅者（解锁 / 记录 / 成就检查）
        LootTrackingBootstrap.registerListeners();
        CatFavorManager.init(Services.CAT);
    }
}
