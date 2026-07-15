package com.meteorite.unsuspiciousblock;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;

/**
 * Fabric PreLaunch 入口：在 Mixin 应用之前检测 Lootr 是否已安装，
 * 若已安装则注册 Lootr 兼容 Mixin 配置。
 * <p>
 * PreLaunchEntrypoint 在 Mixin 处理之前调用，此时
 * {@link FabricLoader#isModLoaded(String)} 已可用。
 * <p>
 * NeoForge 端对应逻辑在 {@code UnsuspiciousBlockNeoForge} 构造函数中。
 */
public class UnsuspiciousBlockFabricPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().isModLoaded("lootr")) {
            Constants.LOG.info("[UnsuspiciousBlock] Lootr detected, registering Lootr compat mixins.");
            Mixins.addConfiguration("unsuspiciousblock.lootr.mixins.json");
        }
    }
}