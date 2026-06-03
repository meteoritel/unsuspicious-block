package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.achievement.ModAchievement;
import com.meteorite.unsuspiciousblock.platform.services.IAchievementHelper;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 基于原版 Advancement API 的成就助手实现。
 * 由于原版 Advancement 系统在 Fabric / NeoForge 上完全一致，无需平台区分。
 */
public class VanillaAchievementHelper implements IAchievementHelper {

    @Override
    // 授予成就：从服务器获取 AdvancementHolder 后调用 award
    public void grant(ServerPlayer player, ModAchievement achievement) {
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

    @Override
    // 检查成就是否已完成
    public boolean has(ServerPlayer player, ModAchievement achievement) {
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

    @Override
    // 撤销成就
    public void revoke(ServerPlayer player, ModAchievement achievement) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder advancement = server.getAdvancements().get(achievement.id());
        if (advancement == null) {
            return;
        }
        player.getAdvancements().revoke(advancement, achievement.criterion());
    }
}
