package com.meteorite.unsuspiciousblock.platform.services;

import java.nio.file.Path;

/** 平台抽象接口——提供平台名、Mod 加载检测、开发环境判断、游戏目录访问 */
public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    default String getModDisplayName(String modId) {
        return "minecraft".equals(modId) ? "Minecraft" : modId;
    }

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    // 获取游戏运行目录（即 .minecraft 所在目录），用于存放运行期产物
    Path getGameDir();

    default java.util.List<com.meteorite.unsuspiciousblock.loottable.injection.DeclaredLootInjection> describeLootInjections(
            net.minecraft.resources.ResourceLocation table) {
        return java.util.List.of();
    }

    default java.util.List<String> lootInjectionHashInputs(net.minecraft.server.packs.resources.ResourceManager resources)
            throws java.io.IOException {
        return java.util.List.of();
    }

    // 获取指定模组的版本号，若未安装或无法获取则返回 null
    @org.jetbrains.annotations.Nullable
    default String getModVersion(String modId) {
        return null;
    }
}
