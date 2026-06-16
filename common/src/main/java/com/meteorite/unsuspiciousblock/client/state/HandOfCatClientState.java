package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;

/**
 * 客户端状态：缓存服务端同步过来的猫之恩惠值，供「猫之手」物品 tooltip 读取。
 * 恩惠值的权威数据在服务端，这里仅作展示缓存。
 */
public final class HandOfCatClientState {
    // 客户端缓存的恩惠值
    private static int cachedFavor;

    private HandOfCatClientState() {
    }

    // 接收服务端同步的恩惠值
    public static void receive(SyncCatFavorPayload payload) {
        cachedFavor = payload.favor();
    }

    // 获取客户端缓存的恩惠值
    public static int getCachedFavor() {
        return cachedFavor;
    }

    // 断线时重置缓存
    public static void reset() {
        cachedFavor = 0;
    }
}
