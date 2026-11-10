package com.meteorite.unsuspiciousblock.client.network;

import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.network.ClientPayloadReceiver;
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

/** 客户端下行处理器，绑定现有状态所有者。 */
public final class ClientPayloadHandlers implements ClientPayloadReceiver {
    public static final ClientPayloadHandlers INSTANCE = new ClientPayloadHandlers();

    private ClientPayloadHandlers() {
    }

    /** 将 SyncArchaeologyCatalogPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncArchaeologyCatalogPayload payload) {
        ArchaeologyJournalClientState.receiveCatalog(payload);
    }

    /** 将 SyncCatalogHashPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncCatalogHashPayload payload) {
        ArchaeologyJournalClientState.receiveCatalogHash(payload);
    }

    /** 将 SyncLootTableManagementPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncLootTableManagementPayload payload) {
        LootTableManagementClientState.receive(payload);
    }

    /** 将 SyncJournalStatePayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncJournalStatePayload payload) {
        ArchaeologyJournalClientState.receiveState(payload);
    }

    /** 将 SyncJournalStateIncrementalPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncJournalStateIncrementalPayload payload) {
        ArchaeologyJournalClientState.receiveStateIncremental(payload);
    }

    /** 将 SyncJournalLogPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncJournalLogPayload payload) {
        ArchaeologyJournalClientState.receiveLogUpdate(payload);
    }

    /** 将 SyncJournalLogSnapshotStartPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncJournalLogSnapshotStartPayload payload) {
        ArchaeologyJournalClientState.beginLogSnapshot(payload);
    }

    /** 将 SyncJournalLogTableChunkPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncJournalLogTableChunkPayload payload) {
        ArchaeologyJournalClientState.receiveLogSnapshotChunk(payload);
    }

    /** 将 SyncJournalLogSnapshotEndPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncJournalLogSnapshotEndPayload payload) {
        ArchaeologyJournalClientState.completeLogSnapshot(payload);
    }

    /** 将 JournalLogDeleteResultPayload 交给对应客户端状态。 */
    @Override
    public void receive(JournalLogDeleteResultPayload payload) {
        ArchaeologyJournalClientState.receiveLogDeleteResult(payload);
    }

    /** 将 SyncCatFavorPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncCatFavorPayload payload) {
        HandOfCatClientState.receive(payload);
    }

    /** 将 SyncReaderScanResultPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncReaderScanResultPayload payload) {
        ReaderScanHudState.receive(payload);
    }

    /** 将 SyncEnchantmentRevealListPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncEnchantmentRevealListPayload payload) {
        EnchantmentRevealClientState.receive(payload);
    }

    /** 将 NotifyTableCompletionRewardPayload 交给对应客户端状态。 */
    @Override
    public void receive(NotifyTableCompletionRewardPayload payload) {
        ArchaeologyJournalClientState.receiveTableCompletionReward(payload);
    }

    /** 将 SyncScenarioCachePayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncScenarioCachePayload payload) {
        ScenarioSimulationClientState.receiveCache(payload);
    }

    /** 将 SyncScenarioResultPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncScenarioResultPayload payload) {
        ScenarioSimulationClientState.receive(payload);
    }

    /** 将 ScenarioRequestRejectedPayload 交给对应客户端状态。 */
    @Override
    public void receive(ScenarioRequestRejectedPayload payload) {
        ScenarioSimulationClientState.receiveRejection(payload);
    }

    /** 将 SyncSimulationAssistPayload 交给对应客户端状态。 */
    @Override
    public void receive(SyncSimulationAssistPayload payload) {
        ScenarioSimulationClientState.receiveAssist(payload);
    }
}
