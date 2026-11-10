package com.meteorite.unsuspiciousblock.platform.services;

import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import java.io.IOException;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
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

    default List<DeclaredLootInjection> describeLootInjections(
            ResourceLocation table) {
        return List.of();
    }

    default List<String> lootInjectionHashInputs(ResourceManager resources)
            throws IOException {
        return List.of();
    }

    // 获取指定模组的版本号，若未安装或无法获取则返回 null
    @org.jetbrains.annotations.Nullable
    default String getModVersion(String modId) {
        return null;
    }
}
