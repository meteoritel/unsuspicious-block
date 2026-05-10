package com.meteorite.unsuspiciousblock.journal;

public final class GameTimeFormatHelper {
    private static final long DAY_TICKS = 24000L;
    private static final long HOUR_TICKS = 1000L;
    private static final long CLOCK_OFFSET = 6000L;

    private GameTimeFormatHelper() {
    }

    public static GameTimeParts fromTime(long gameTime, long dayTime) {
        long normalizedGameTime = Math.max(0L, gameTime);
        long normalizedDayTime = Math.max(0L, dayTime);
        int day = (int) (normalizedGameTime / DAY_TICKS) + 1;
        long clockTicks = Math.floorMod(normalizedDayTime + CLOCK_OFFSET, DAY_TICKS);
        int hour = (int) (clockTicks / HOUR_TICKS);
        int minute = (int) ((clockTicks % HOUR_TICKS) * 60L / HOUR_TICKS);
        return new GameTimeParts(day, hour, minute);
    }

    public static GameTimeParts fromGameTime(long gameTime) {
        return fromTime(gameTime, gameTime);
    }

    public record GameTimeParts(int day, int hour, int minute) {
    }
}
