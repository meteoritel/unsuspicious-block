package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;

/**
 * Fabric PreLaunch 入口：在 Mixin 应用之前检测 Lootr 是否已安装，
 * 若已安装则注册 Lootr 兼容 Mixin 配置。
 * <p>
 * PreLaunchEntrypoint 在 Mixin 处理之前调用，此时平台服务已可用于 Mod 加载检测。
 * <p>
 * NeoForge 端通过 neoforge.mods.toml 的 requiredMods 条件在加载器阶段注册。
 */
public class UnsuspiciousBlockFabricPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        if (Services.PLATFORM.isModLoaded("lootr")) {
            Constants.LOG.info("[UnsuspiciousBlock] Lootr detected, registering Lootr compat mixins.");
            Mixins.addConfiguration("unsuspiciousblock.lootr.mixins.json");
        }
    }
}
