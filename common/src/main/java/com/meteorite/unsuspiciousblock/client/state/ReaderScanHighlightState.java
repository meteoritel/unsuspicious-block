package com.meteorite.unsuspiciousblock.client.state;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 客户端状态：缓存最近一次范围扫描的可疑方块位置，并维护描边显示倒计时。
 * 显示总时长 10 秒（200 ticks），前 2 秒闪烁以吸引注意，之后持续显示直到消失。
 */
public final class ReaderScanHighlightState {
    // 总显示时长：10 秒 = 200 ticks
    private static final int TOTAL_TICKS = 200;
    // 闪烁阶段时长：前 2 秒 = 40 ticks
    private static final int BLINK_TICKS = 40;
    // 闪烁节奏：每 4 tick 切换一次可见性
    private static final int BLINK_PERIOD = 4;

    private static List<BlockPos> highlightedBlocks = List.of();
    private static int remainingTicks = 0;
    // 自接收以来的累计 tick，用于控制闪烁节奏
    private static int elapsedTicks = 0;

    private ReaderScanHighlightState() {
    }

    // 接收服务端同步的扫描结果，重置倒计时
    public static void receive(List<BlockPos> blocks) {
        highlightedBlocks = List.copyOf(blocks);
        remainingTicks = TOTAL_TICKS;
        elapsedTicks = 0;
    }

    // 每客户端 tick 递减倒计时
    public static void tick() {
        if (remainingTicks > 0) {
            remainingTicks--;
            elapsedTicks++;
        }
    }

    // 返回当前帧应渲染的方块列表；闪烁的不可见帧返回空列表
    public static List<BlockPos> getRenderBlocks() {
        if (remainingTicks <= 0) return List.of();
        // 闪烁阶段：前 BLINK_TICKS 内按节奏切换可见性
        if (elapsedTicks < BLINK_TICKS) {
            if ((elapsedTicks / BLINK_PERIOD) % 2 != 0) {
                return List.of();
            }
        }
        return highlightedBlocks;
    }

    // 断线时清除状态
    public static void reset() {
        highlightedBlocks = List.of();
        remainingTicks = 0;
        elapsedTicks = 0;
    }
}
