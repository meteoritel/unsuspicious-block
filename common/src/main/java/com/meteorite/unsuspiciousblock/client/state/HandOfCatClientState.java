package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;

/**
 * 客户端状态：缓存服务端同步过来的猫之恩惠值与九命命数，
 * 供「猫之手」物品 tooltip 与快捷栏 HUD 读取。
 * 恩惠值与命数的权威数据在服务端，这里仅作展示缓存。
 */
public final class HandOfCatClientState {
    // 客户端缓存的恩惠值
    private static int cachedFavor;
    // 客户端缓存的九命命数
    private static int cachedNineLivesCount;

    private HandOfCatClientState() {
    }

    // 接收服务端同步的恩惠值与命数
    public static void receive(SyncCatFavorPayload payload) {
        cachedFavor = payload.favor();
        cachedNineLivesCount = payload.nineLivesCount();
    }

    // 获取客户端缓存的恩惠值
    public static int getCachedFavor() {
        return cachedFavor;
    }

    // 获取客户端缓存的九命命数
    public static int getCachedNineLivesCount() {
        return cachedNineLivesCount;
    }

    // 断线时重置缓存
    public static void reset() {
        cachedFavor = 0;
        cachedNineLivesCount = 0;
    }
}
