package com.meteorite.unsuspiciousblock.platform.services;

import com.meteorite.unsuspiciousblock.achievement.ModAchievement;
import net.minecraft.server.level.ServerPlayer;

/**
 * 成就平台抽象接口——封装原版 Advancement API 调用。
 * 当前实现为 {@code VanillaAchievementHelper}，直接使用原版 API
 * （Fabric 与 NeoForge 均共享相同的原版 Advancement 系统）。
 */
public interface IAchievementHelper {

    // 授予玩家成就的指定 criterion
    void grant(ServerPlayer player, ModAchievement achievement);

    // 检查玩家是否已获得成就
    boolean has(ServerPlayer player, ModAchievement achievement);

    // 撤销玩家成就的指定 criterion
    void revoke(ServerPlayer player, ModAchievement achievement);
}
