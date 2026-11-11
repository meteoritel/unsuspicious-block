package com.meteorite.unsuspiciousblock.core.bootstrap;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.cat.merchant.MerchantCatSpawner;
import com.meteorite.unsuspiciousblock.core.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.core.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import com.meteorite.unsuspiciousblock.core.world.StructureRewindService;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** 按具名相位执行服务端依赖顺序，停止时先完成玩家落盘。 */
public final class ModServerLifecycle {
    private static final Entry CONFIG = new Entry("loot-config", ServerLootTableConfigManager::start,
            ServerLootTableConfigManager::tick, server -> ServerLootTableConfigManager.stop());
    private static final Entry PLAYERS = new Entry("journal-players", JournalPlayerDataService::onServerStarted,
            JournalPlayerDataService::onServerTick, JournalPlayerDataService::onServerStopped);
    private static final Entry WORKER = new Entry("simulation-worker", server -> LootProbabilitySimulationWorker.start(),
            LootProbabilitySimulationWorker::tickIfPresent, server -> LootProbabilitySimulationWorker.stop());
    private static final Entry CATALOG = new Entry("journal-catalog", ArchaeologyJournalServerCatalog::ensureLoaded,
            null, server -> ArchaeologyJournalServerCatalog.invalidate());
    private static final Entry MERCHANT = new Entry("merchant-cat", null, MerchantCatSpawner::tick, null);
    private static final Entry SHIMMER = new Entry("shimmer", null, ShimmerSpawnService::tick, ShimmerSpawnService::stop);
    private static final Entry REWIND = new Entry("structure-rewind", null,
            StructureRewindService::tick, StructureRewindService::stop);
    private static final Entry BONE_BREAKS = new Entry("bone-breaks", null, null,
            server -> NaturalBoneBlockTracker.clearPendingPlayerBreaks());

    private static final List<Entry> START = List.of(CONFIG, PLAYERS, WORKER, CATALOG);
    private static final List<Entry> TICK = List.of(PLAYERS, CONFIG, WORKER, MERCHANT, SHIMMER, REWIND);
    // 玩家落盘仍需 worker、目录与配置可用，不能直接逆序释放。
    private static final List<Entry> STOP = List.of(PLAYERS, WORKER, CATALOG, CONFIG, BONE_BREAKS, SHIMMER, REWIND);

    private ModServerLifecycle() {
    }

    public static void started(MinecraftServer server) {
        execute(Phase.START, START, server);
    }

    public static void tick(MinecraftServer server) {
        execute(Phase.TICK, TICK, server);
    }

    public static void stopped(MinecraftServer server) {
        execute(Phase.STOP, STOP, server);
    }

    private static void execute(Phase phase, List<Entry> entries, MinecraftServer server) {
        RuntimeException failure = null;
        for (Entry entry : entries) {
            Consumer<MinecraftServer> action = switch (phase) {
                case START -> entry.start();
                case TICK -> entry.tick();
                case STOP -> entry.stop();
            };
            if (action == null) throw new IllegalStateException("Missing " + phase + " action for " + entry.name());
            try {
                action.accept(server);
            } catch (RuntimeException exception) {
                Constants.LOG.error(Constants.LOG_TAG + "Lifecycle {} failed at {}", phase, entry.name(), exception);
                if (phase != Phase.STOP) throw exception;
                // 单项停止失败仍释放其余服务器引用，最后报告原始失败。
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private enum Phase { START, TICK, STOP }

    private record Entry(String name, @Nullable Consumer<MinecraftServer> start,
                         @Nullable Consumer<MinecraftServer> tick, @Nullable Consumer<MinecraftServer> stop) {
    }
}
