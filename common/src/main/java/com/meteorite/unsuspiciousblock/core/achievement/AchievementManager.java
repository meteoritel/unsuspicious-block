package com.meteorite.unsuspiciousblock.core.achievement;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 游戏成就授予与查询的统一入口，双平台直接使用原版 Advancement。 */
public final class AchievementManager {
    private AchievementManager() {}

    // 授予成就：从服务器获取 AdvancementHolder 后调用 award
    public static void grant(ServerPlayer player, ModAchievement achievement) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder advancement = server.getAdvancements().get(achievement.id());
        if (advancement == null) {
            return;
        }
        player.getAdvancements().award(advancement, achievement.criterion());
    }

    // 检查成就是否已完成
    public static boolean has(ServerPlayer player, ModAchievement achievement) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        AdvancementHolder advancement = server.getAdvancements().get(achievement.id());
        if (advancement == null) {
            return false;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        return progress.isDone();
    }

    // 若未获得则授予，返回是否实际授予
    public static boolean grantIfNotAlready(ServerPlayer player, ModAchievement achievement) {
        if (has(player, achievement)) {
            return false;
        }
        grant(player, achievement);
        return true;
    }
}
