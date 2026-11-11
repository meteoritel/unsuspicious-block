package com.meteorite.unsuspiciousblock.platform.services;

import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import java.io.IOException;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import java.nio.file.Path;

/** 平台抽象接口——提供平台名、Mod 加载检测、游戏目录与注入描述 */
public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    default String getModDisplayName(String modId) {
        return "minecraft".equals(modId) ? "Minecraft" : modId;
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

}
