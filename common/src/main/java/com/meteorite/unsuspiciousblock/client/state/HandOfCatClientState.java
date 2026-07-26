package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.client.ui.toast.CatBondToast;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * 客户端状态：缓存服务端同步过来的猫族关系、猫族羁绊与九命命数，
 * 供「猫之手」物品 tooltip 与快捷栏 HUD 读取。
 * 恩惠值与命数的权威数据在服务端，这里仅作展示缓存。
 */
public final class HandOfCatClientState {
    private static int cachedCatBond;
    // 客户端缓存的九命命数
    private static int cachedNineLivesCount;
    private static boolean relationshipEstablished;
    private static boolean initialized;

    private HandOfCatClientState() {
    }

    // 接收服务端同步的猫族关系状态。
    public static void receive(SyncCatFavorPayload payload) {
        int previousBond = cachedCatBond;
        int previousLives = cachedNineLivesCount;
        boolean previousRelationship = relationshipEstablished;
        cachedCatBond = payload.catBond();
        cachedNineLivesCount = payload.nineLivesCount();
        relationshipEstablished = payload.relationshipEstablished();
        if (!initialized) {
            initialized = true;
            return;
        }
        if (!previousRelationship && relationshipEstablished) {
            CatBondToast.showRelationshipEstablished();
            return;
        }
        CatBondStage oldStage = CatBondStage.fromBond(previousBond);
        CatBondStage newStage = CatBondStage.fromBond(cachedCatBond);
        if (oldStage != newStage) {
            CatBondToast.showStageChange(oldStage, newStage,
                    previousLives > 0 && cachedNineLivesCount == 0);
        }
    }

    // 获取客户端缓存的恩惠值
    public static int getCachedFavor() {
        return cachedCatBond;
    }

    public static int getCachedCatBond() {
        return cachedCatBond;
    }

    // 获取客户端缓存的九命命数
    public static int getCachedNineLivesCount() {
        return cachedNineLivesCount;
    }

    public static boolean isRelationshipEstablished() {
        return relationshipEstablished;
    }

    // 判断给定 UUID 是否属于当前客户端玩家。
    public static boolean isLocalPlayer(UUID playerUuid) {
        return Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.getUUID().equals(playerUuid);
    }

    // 断线时重置缓存
    public static void reset() {
        cachedCatBond = 0;
        cachedNineLivesCount = 0;
        relationshipEstablished = false;
        initialized = false;
    }
}
