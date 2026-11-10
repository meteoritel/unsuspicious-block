package com.meteorite.unsuspiciousblock.core.network;

import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogTableChunkPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogDeleteResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatFavorPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncEnchantmentRevealListPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncSimulationAssistPayload;

/** 下行消息接收契约，公共通道声明仅依赖跨端 payload。 */
public interface ClientPayloadReceiver {
    /** 接收 SyncArchaeologyCatalogPayload，由客户端实现应用状态。 */
    void receive(SyncArchaeologyCatalogPayload payload);

    /** 接收 SyncCatalogHashPayload，由客户端实现应用状态。 */
    void receive(SyncCatalogHashPayload payload);

    /** 接收 SyncLootTableManagementPayload，由客户端实现应用状态。 */
    void receive(SyncLootTableManagementPayload payload);

    /** 接收 SyncJournalStatePayload，由客户端实现应用状态。 */
    void receive(SyncJournalStatePayload payload);

    /** 接收 SyncJournalStateIncrementalPayload，由客户端实现应用状态。 */
    void receive(SyncJournalStateIncrementalPayload payload);

    /** 接收 SyncJournalLogPayload，由客户端实现应用状态。 */
    void receive(SyncJournalLogPayload payload);

    /** 接收 SyncJournalLogSnapshotStartPayload，由客户端实现应用状态。 */
    void receive(SyncJournalLogSnapshotStartPayload payload);

    /** 接收 SyncJournalLogTableChunkPayload，由客户端实现应用状态。 */
    void receive(SyncJournalLogTableChunkPayload payload);

    /** 接收 SyncJournalLogSnapshotEndPayload，由客户端实现应用状态。 */
    void receive(SyncJournalLogSnapshotEndPayload payload);

    /** 接收 JournalLogDeleteResultPayload，由客户端实现应用状态。 */
    void receive(JournalLogDeleteResultPayload payload);

    /** 接收 SyncCatFavorPayload，由客户端实现应用状态。 */
    void receive(SyncCatFavorPayload payload);

    /** 接收 SyncReaderScanResultPayload，由客户端实现应用状态。 */
    void receive(SyncReaderScanResultPayload payload);

    /** 接收 SyncEnchantmentRevealListPayload，由客户端实现应用状态。 */
    void receive(SyncEnchantmentRevealListPayload payload);

    /** 接收 NotifyTableCompletionRewardPayload，由客户端实现应用状态。 */
    void receive(NotifyTableCompletionRewardPayload payload);

    /** 接收 SyncScenarioCachePayload，由客户端实现应用状态。 */
    void receive(SyncScenarioCachePayload payload);

    /** 接收 SyncScenarioResultPayload，由客户端实现应用状态。 */
    void receive(SyncScenarioResultPayload payload);

    /** 接收 ScenarioRequestRejectedPayload，由客户端实现应用状态。 */
    void receive(ScenarioRequestRejectedPayload payload);

    /** 接收 SyncSimulationAssistPayload，由客户端实现应用状态。 */
    void receive(SyncSimulationAssistPayload payload);
}
