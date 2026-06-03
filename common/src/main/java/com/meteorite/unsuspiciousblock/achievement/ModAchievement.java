package com.meteorite.unsuspiciousblock.achievement;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;

/**
 * 模组成就接口——定义每个成就的基本元数据。
 * 具体成就通过 {@link ModAchievements} 枚举统一管理。
 */
public interface ModAchievement {

    /** 成就的 advancement 路径（相对于 data/{modid}/advancements/，不含 .json 后缀） */
    String path();

    /** advancement JSON 中定义的 criterion 键名 */
    String criterion();

    /** 完整的 ResourceLocation 标识 */
    default ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path());
    }
}
