package com.meteorite.unsuspiciousblock.text;

import java.util.UUID;

/***
 * 客户端 tooltip 数据桥。
 * <p>
 * 服务端可达类（物品、方块展示）只能依赖本类，不能直接 import {@code client} 包：
 * 专用服务端不会调用 {@link #install}，所有查询返回缺省值，因此不会加载任何客户端类；
 * 客户端入口（fabric / neoforge）在初始化时安装 {@link Hooks} 实现。
 * <p>
 * 未安装实现时的缺省语义：Shift 展开视为未按下、打开界面为空操作、
 * 猫族数据为 0、任何 UUID 都不是本地玩家。
 */
public final class ClientTooltipBridge {

    // 仅客户端可用的能力；实现类位于 client 包，且只由客户端入口安装
    public interface Hooks {

        // Shift 是否按下（控制 tooltip 是否展开详情）
        boolean isExpandKeyDown();

        // 打开考古笔记界面
        void openJournal();

        // 客户端缓存的猫族羁绊恩惠值
        int handOfCatFavor();

        // 客户端缓存的九命命数
        int handOfCatLives();

        // 给定 UUID 是否属于当前客户端玩家
        boolean isLocalPlayer(UUID playerUuid);
    }

    private static volatile Hooks hooks;

    private ClientTooltipBridge() {
    }

    // 安装客户端实现；只在客户端入口各调用一次
    public static void install(Hooks implementation) {
        hooks = implementation;
    }

    public static boolean isExpandKeyDown() {
        Hooks current = hooks;
        return current != null && current.isExpandKeyDown();
    }

    public static void openJournal() {
        Hooks current = hooks;
        if (current != null) {
            current.openJournal();
        }
    }

    public static int handOfCatFavor() {
        Hooks current = hooks;
        return current == null ? 0 : current.handOfCatFavor();
    }

    public static int handOfCatLives() {
        Hooks current = hooks;
        return current == null ? 0 : current.handOfCatLives();
    }

    public static boolean isLocalPlayer(UUID playerUuid) {
        Hooks current = hooks;
        return current != null && current.isLocalPlayer(playerUuid);
    }
}
