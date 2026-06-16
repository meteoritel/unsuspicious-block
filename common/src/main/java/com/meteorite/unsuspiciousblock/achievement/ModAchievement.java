package com.meteorite.unsuspiciousblock.achievement;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;

/**
 * 模组成就接口——定义每个成就的基本元数据。
 * 具体成就通过 {@link ModAchievements} 枚举统一管理。
 */
public interface ModAchievement {

    String path();
    String criterion();

    default ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path());
    }
}
