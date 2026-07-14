package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.LootProbabilityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/** 目录同步处理器——管理目录的哈希校验按需同步与全量下发 */
public final class JournalCatalogHandler {
    private JournalCatalogHandler() {}

    // 向玩家发送目录哈希（用于按需同步比对）
    public static void syncCatalogHash(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        String hash = ArchaeologyJournalServerCatalog.computeCatalogHash();
        if (hash.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncCatalogHashPayload(hash));
    }

    // 向玩家发送完整目录（客户端请求后调用）
    public static void syncFullCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) return;

        // 做快照：NeoForge 的 C2S handler 在网络线程执行，
        // 若不拷贝，主线程 worker 并发 put 会导致 ConcurrentHashMap 的 size() 与迭代器不一致
        // （encode 先写 size 再遍历，并发 put 会让迭代器多出一个条目，客户端解码后剩余字节 → "bytes extra"）
        Map<ResourceLocation, TableDefinition> snapshot = new LinkedHashMap<>(catalog);
        Services.NETWORK.sendToPlayer(player, new SyncArchaeologyCatalogPayload(snapshot));
    }

    // 处理客户端请求完整目录
    public static void handleRequestCatalog(ServerPlayer player) {
        syncFullCatalog(player);
    }

    /** 数据包重载：暂停 worker → 失效内存目录 → 重新解析入队 → 恢复 worker → 同步哈希 */
    public static void onDataPackReload(MinecraftServer server) {
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker != null) worker.pauseForReload();
        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        if (worker != null) worker.resumeAfterReload();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncCatalogHash(player);
        }
    }

    /**
     * 强制清空概率缓存并重新模拟所有表（异步语义）。
     * 清空 SavedData + 内存目录 → 重新解析原始目录 → 全部表入队后台模拟。
     * 玩家会随模拟完成渐进收到哈希更新。
     */
    public static void forceFlushCatalog(MinecraftServer server) {
        LootProbabilityData probabilityData = LootProbabilityData.get(server.overworld());
        probabilityData.clear();

        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker != null) worker.clearQueue();

        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
    }
}
