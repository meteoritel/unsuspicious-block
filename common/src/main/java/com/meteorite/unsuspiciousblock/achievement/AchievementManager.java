package com.meteorite.unsuspiciousblock.achievement;

import com.meteorite.unsuspiciousblock.platform.services.IAchievementHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * 成就管理器——游戏逻辑与平台成就系统的桥梁。
 * <p>
 * 调用链：游戏逻辑 → AchievementManager → IAchievementHelper → Vanilla Advancement
 * <p>
 * 所有成就触发均应从游戏逻辑调用本类的静态方法，
 * 由本类委托平台层最终操作原版 Advancement API。
 */
public final class AchievementManager {

    private static IAchievementHelper helper;

    private AchievementManager() {
    }

    // 由 Common init 调用注入平台实现
    public static void init(IAchievementHelper helper) {
        AchievementManager.helper = helper;
    }

    // 直接授予成就
    public static void grant(ServerPlayer player, ModAchievement achievement) {
        if (helper == null) {
            return;
        }
        helper.grant(player, achievement);
    }

    // 检查是否已获得成就
    public static boolean has(ServerPlayer player, ModAchievement achievement) {
        if (helper == null) {
            return false;
        }
        return helper.has(player, achievement);
    }

    // 若未获得则授予，返回是否实际授予
    public static boolean grantIfNotAlready(ServerPlayer player, ModAchievement achievement) {
        if (has(player, achievement)) {
            return false;
        }
        grant(player, achievement);
        return true;
    }

    // 撤销成就
    public static void revoke(ServerPlayer player, ModAchievement achievement) {
        if (helper == null) {
            return;
        }
        helper.revoke(player, achievement);
    }
}
