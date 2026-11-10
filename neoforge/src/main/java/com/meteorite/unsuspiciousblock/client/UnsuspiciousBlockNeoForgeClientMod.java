package com.meteorite.unsuspiciousblock.client;

import com.meteorite.unsuspiciousblock.Constants;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import com.meteorite.unsuspiciousblock.client.config.NeoForgeConfigScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** 物理客户端入口，注册基于已注册 spec 的 kit 配置页面。 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class UnsuspiciousBlockNeoForgeClientMod {

    public UnsuspiciousBlockNeoForgeClientMod(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, NeoForgeConfigScreen::new);
    }
}
