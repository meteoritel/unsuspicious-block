package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.services.IPanningConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/*** 按维度统计真实自然生成；仅服务端线程使用，临时节拍不写入配置或账本。 */
public final class ShimmerSpawnStatistics {
    private static final String KEY = "command.unsuspiciousblock.usb.shimmer.rate.";
    private static final Map<ResourceKey<Level>, Session> SESSIONS = new HashMap<>();

    private ShimmerSpawnStatistics() {
    }

    // 每个维度最多一场统计；结束结果保留到下一次开始或服务器停止。
    public static int start(CommandSourceStack source, int seconds, int intervalTicks) {
        ServerLevel level = source.getLevel();
        if (isRunning(level)) {
            source.sendFailure(Component.translatable(KEY + "already_running"));
            return 0;
        }
        if (seconds * 20 < intervalTicks) {
            source.sendFailure(Component.translatable(KEY + "too_short", intervalTicks));
            return 0;
        }
        SESSIONS.put(level.dimension(), new Session(source, seconds * 20, intervalTicks));
        source.sendSuccess(() -> Component.translatable(KEY + "started",
                level.dimension().location().toString(), seconds, intervalTicks,
                IPanningConfig.DEFAULT_SPAWN_INTERVAL_TICKS), false);
        return 1;
    }

    public static boolean isRunning(ServerLevel level) {
        Session session = SESSIONS.get(level.dimension());
        return session != null && session.running;
    }

    // 使用实际服务器 tick 计时，避免 time set、暂停和低 TPS 扭曲游戏时间口径。
    static void tick(MinecraftServer server) {
        for (var entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (!session.running) continue;
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) continue;
            session.elapsedTicks++;
            if (session.elapsedTicks % session.intervalTicks == 0) {
                session.attempts++;
                if (ShimmerSpawnService.attemptNaturalSpawn(level, true)) session.successes++;
            }
            if (session.elapsedTicks >= session.durationTicks) {
                finish(level, session);
                report(session.source, session);
            }
        }
    }

    public static int status(CommandSourceStack source, boolean stop) {
        Session session = SESSIONS.get(source.getLevel().dimension());
        if (session == null) {
            source.sendFailure(Component.translatable(KEY + "missing"));
            return 0;
        }
        if (stop && session.running) finish(source.getLevel(), session);
        report(source, session);
        return 1;
    }

    // 结束后重新按当前配置等待一个完整间隔，不留下加速尝试的持久化时间。
    private static void finish(ServerLevel level, Session session) {
        session.running = false;
        ShimmerLedger.of(level).scheduleNextAttempt(level.getGameTime(),
                Services.PANNING_CONFIG.getSpawnIntervalTicks());
    }

    private static void report(CommandSourceStack source, Session session) {
        double actualPerMinute = session.elapsedTicks == 0 ? 0.0D
                : session.successes * 1200.0D / session.elapsedTicks;
        double defaultPerMinute = actualPerMinute * session.intervalTicks
                / IPanningConfig.DEFAULT_SPAWN_INTERVAL_TICKS;
        source.sendSuccess(() -> Component.translatable(KEY + "report",
                source.getLevel().dimension().location().toString(),
                Component.translatable(KEY + (session.running ? "running" : "finished")),
                number(session.elapsedTicks / 20.0D), session.intervalTicks, session.attempts,
                session.successes, number(actualPerMinute), number(defaultPerMinute),
                number(defaultPerMinute * 60.0D)), false);
        source.sendSuccess(() -> Component.translatable(KEY + "limits"), false);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static void clear() {
        SESSIONS.clear();
    }

    /*** 一场统计的固定参数与累计计数；不持有实体、不扫描或强制加载区块。 */
    private static final class Session {
        private final CommandSourceStack source;
        private final int durationTicks;
        private final int intervalTicks;
        private int elapsedTicks;
        private int attempts;
        private int successes;
        private boolean running = true;

        private Session(CommandSourceStack source, int durationTicks, int intervalTicks) {
            this.source = source;
            this.durationTicks = durationTicks;
            this.intervalTicks = intervalTicks;
        }
    }
}
