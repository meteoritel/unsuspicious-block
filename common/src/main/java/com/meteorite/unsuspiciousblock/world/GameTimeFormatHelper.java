package com.meteorite.unsuspiciousblock.world;

/**
 * 游戏时间格式化工具——将游戏刻（gameTime / dayTime）转换为可读的天 / 时 / 分。
 * 基于 Minecraft 昼夜循环（24000 刻/天），偏移 6000 刻使 0 刻对应早晨 6:00。
 */
public final class GameTimeFormatHelper {
    private static final long DAY_TICKS = 24000L;
    private static final long HOUR_TICKS = 1000L;
    private static final long CLOCK_OFFSET = 6000L;

    private GameTimeFormatHelper() {
    }

    // 将游戏刻时间转换为可读的天/时/分（使用 dayTime 计算钟表时间）
    public static GameTimeParts fromTime(long gameTime, long dayTime) {
        long normalizedGameTime = Math.max(0L, gameTime);
        long normalizedDayTime = Math.max(0L, dayTime);
        int day = (int) (normalizedGameTime / DAY_TICKS) + 1;
        long clockTicks = Math.floorMod(normalizedDayTime + CLOCK_OFFSET, DAY_TICKS);
        int hour = (int) (clockTicks / HOUR_TICKS);
        int minute = (int) ((clockTicks % HOUR_TICKS) * 60L / HOUR_TICKS);
        return new GameTimeParts(day, hour, minute);
    }

    // 仅使用 gameTime 的简化版本（dayTime 与 gameTime 相同时使用）
    public static GameTimeParts fromGameTime(long gameTime) {
        return fromTime(gameTime, gameTime);
    }

    public record GameTimeParts(int day, int hour, int minute) {
        // 格式化为零补齐的 HH:MM 时钟字符串，确保多行时间刻度对齐
        public String formattedClock() {
            return String.format("%02d:%02d", hour, minute);
        }
    }
}
