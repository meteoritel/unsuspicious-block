package com.meteorite.unsuspiciousblock.core.bootstrap;

import com.meteorite.unsuspiciousblock.core.cat.merchant.MerchantCatSpawner;
import com.meteorite.unsuspiciousblock.core.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.core.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import com.meteorite.unsuspiciousblock.core.world.StructureRewindService;
import net.minecraft.server.MinecraftServer;

public final class ModServerLifecycle {
    private ModServerLifecycle() {
    }

    public static void started(MinecraftServer server) {
        ServerLootTableConfigManager.start(server);
        JournalPlayerDataService.onServerStarted(server);
        LootProbabilitySimulationWorker.start();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
    }

    public static void tick(MinecraftServer server) {
        JournalPlayerDataService.onServerTick(server);
        ServerLootTableConfigManager.tick(server);
        LootProbabilitySimulationWorker.tickIfPresent(server);
        MerchantCatSpawner.tick(server);
        ShimmerSpawnService.tick(server);
        StructureRewindService.tick(server);
    }

    public static void stopped(MinecraftServer server) {
        // 玩家落盘仍需 worker、目录与配置可用。
        JournalPlayerDataService.onServerStopped(server);
        LootProbabilitySimulationWorker.stop();
        ArchaeologyJournalServerCatalog.invalidate();
        ServerLootTableConfigManager.stop();
        NaturalBoneBlockTracker.clearPendingPlayerBreaks();
        ShimmerSpawnService.stop(server);
        StructureRewindService.stop(server);
    }
}
