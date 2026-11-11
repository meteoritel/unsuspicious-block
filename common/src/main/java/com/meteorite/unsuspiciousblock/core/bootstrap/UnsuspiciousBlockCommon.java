package com.meteorite.unsuspiciousblock.core.bootstrap;

import com.meteorite.unsuspiciousblock.Constants;

import com.meteorite.unsuspiciousblock.core.enchantment.framework.EnchantmentEffects;
import com.meteorite.unsuspiciousblock.core.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.core.enchantment.reveal.EnchantmentRevealConditions;
import com.meteorite.unsuspiciousblock.core.journal.tracking.event.LootTrackingBootstrap;
import com.meteorite.unsuspiciousblock.core.loottable.condition.ModLootConditions;
import com.meteorite.unsuspiciousblock.platform.Services;

import java.util.function.Supplier;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

/** Common 模块统一入口，由各平台模块调用 */
public class UnsuspiciousBlockCommon {
    /** 平台交付条件类型的延迟引用，初始化时不解析尚未绑定的 holder。 */
    public static void init(Supplier<LootItemConditionType> toolEnchantmentType) {
        ModLootConditions.registerAnalysisHandlers(toolEnchantmentType);
        Constants.LOG.info(Constants.LOG_TAG + "Common init start (platform={})", Services.PLATFORM.getPlatformName());
        EnchantmentManager.init(Services.ENCHANTMENT);
        EnchantmentEffects.registerAll();
        // 注册附魔揭示内建条件（服务端评估，决定是否下发完整候选列表）
        EnchantmentRevealConditions.register();
        // 注册战利品发现事件的内建订阅者（解锁 / 记录 / 成就检查）
        LootTrackingBootstrap.registerListeners();
        Services.CAT.registerListeners();
    }
}
