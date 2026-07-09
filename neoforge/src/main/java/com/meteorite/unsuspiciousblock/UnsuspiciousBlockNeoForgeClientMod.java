package com.meteorite.unsuspiciousblock;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge 客户端 mod 入口--仅在物理客户端加载
 * 注册 NeoForge 内置 ConfigurationScreen 作为模组配置界面，
 * 基于 NeoForgeLootTableConfig.CONFIG_SPEC 自动生成可编辑控件
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class UnsuspiciousBlockNeoForgeClientMod {

    public UnsuspiciousBlockNeoForgeClientMod(ModContainer container) {
        // ConfigurationScreen::new 匹配 IConfigScreenFactory 的 (ModContainer, Screen) -> Screen 签名
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
