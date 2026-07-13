package com.meteorite.unsuspiciousblock.client.state;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 客户端状态：缓存最近一次范围扫描的高亮方块位置，并维护描边显示倒计时。
 * <p>分两类存储：可疑方块（红色描边）与含战利品表的容器（紫色描边，仅高亮）。
 * 显示总时长 10 秒（200 ticks），前 2 秒闪烁以吸引注意，之后持续显示直到消失。
 */
public final class ReaderScanHighlightState {
    // 总显示时长：10 秒 = 200 ticks
    private static final int TOTAL_TICKS = 200;
    // 闪烁阶段时长：前 2 秒 = 40 ticks
    private static final int BLINK_TICKS = 40;
    // 闪烁节奏：每 4 tick 切换一次可见性
    private static final int BLINK_PERIOD = 4;

    private static List<BlockPos> highlightedSuspicious = List.of();
    private static List<BlockPos> highlightedLootContainers = List.of();
    private static int remainingTicks = 0;
    // 自接收以来的累计 tick，用于控制闪烁节奏
    private static int elapsedTicks = 0;

    private ReaderScanHighlightState() {
    }

    // 接收服务端同步的扫描结果，重置倒计时
    public static void receive(List<BlockPos> suspiciousBlocks, List<BlockPos> lootContainers) {
        highlightedSuspicious = List.copyOf(suspiciousBlocks);
        highlightedLootContainers = List.copyOf(lootContainers);
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

    // 返回当前帧应渲染的可疑方块列表；闪烁的不可见帧返回空列表
    public static List<BlockPos> getRenderSuspiciousBlocks() {
        return getRenderList(highlightedSuspicious);
    }

    // 返回当前帧应渲染的战利品容器列表；闪烁的不可见帧返回空列表
    public static List<BlockPos> getRenderLootContainers() {
        return getRenderList(highlightedLootContainers);
    }

    // 共用的闪烁判定逻辑：两类高亮共用同一倒计时与闪烁节奏
    private static List<BlockPos> getRenderList(List<BlockPos> source) {
        if (remainingTicks <= 0) return List.of();
        if (elapsedTicks < BLINK_TICKS) {
            if ((elapsedTicks / BLINK_PERIOD) % 2 != 0) {
                return List.of();
            }
        }
        return source;
    }

    // 断线时清除状态
    public static void reset() {
        highlightedSuspicious = List.of();
        highlightedLootContainers = List.of();
        remainingTicks = 0;
        elapsedTicks = 0;
    }
}
