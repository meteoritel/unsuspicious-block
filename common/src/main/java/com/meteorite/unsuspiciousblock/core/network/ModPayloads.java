package com.meteorite.unsuspiciousblock.core.network;

import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioCachePayload;

import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.core.network.journal.SimulationAssistHandler;
import com.meteorite.unsuspiciousblock.core.cat.CatNetworkHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.LootTableManagementHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalStateHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.ReaderScanLevelHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.ScenarioSimulationHandler;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.CatDeterrenceTogglePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.CatLightStepTogglePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalStateFullPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioSimulationPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatFavorPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncEnchantmentRevealListPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogTableChunkPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogDeleteResultPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import java.util.function.BiConsumer;

/** 公共网络声明：维护通道类型、codec 与 C2S 服务端处理函数。 */
public final class ModPayloads {
    public static final String PROTOCOL_VERSION = "4.12";

    private ModPayloads() {
    }

    /** C2S payload 描述：类型 + 编解码 + 服务端处理函数 */
    public record C2S<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
            BiConsumer<ServerPlayer, T> handler) {
    }

    /** C2S payload 列表（服务端处理） */
    public static final List<C2S<?>> C2S_PAYLOADS = List.of(
            new C2S<>(RequestSimulationAssistPayload.TYPE, RequestSimulationAssistPayload.STREAM_CODEC, SimulationAssistHandler::handle),
            new C2S<>(UpdateReaderScanLevelPayload.TYPE, UpdateReaderScanLevelPayload.STREAM_CODEC,
                    (player, payload) -> ReaderScanLevelHandler.handleUpdateReaderScanLevel(payload, player)),
            new C2S<>(RequestCatalogPayload.TYPE, RequestCatalogPayload.STREAM_CODEC,
                    (player, payload) -> JournalCatalogHandler.handleRequestCatalog(player)),
            new C2S<>(RequestLootTableManagementPayload.TYPE, RequestLootTableManagementPayload.STREAM_CODEC,
                    (player, payload) -> LootTableManagementHandler.handleRequest(player)),
            new C2S<>(UpdateTrackedLootTablePayload.TYPE, UpdateTrackedLootTablePayload.STREAM_CODEC,
                    LootTableManagementHandler::handleUpdate),
            new C2S<>(UpdateLootTableTranslationsPayload.TYPE,
                    UpdateLootTableTranslationsPayload.STREAM_CODEC,
                    LootTableManagementHandler::handleTranslationUpdate),
            new C2S<>(RequestJournalStateFullPayload.TYPE, RequestJournalStateFullPayload.STREAM_CODEC,
                    (player, payload) -> JournalStateHandler.handleRequestFull(player)),
            new C2S<>(RequestJournalLogSnapshotPayload.TYPE, RequestJournalLogSnapshotPayload.STREAM_CODEC,
                    (player, payload) -> JournalLogHandler.handleRequestSnapshot(player)),
            new C2S<>(UpdateJournalLogNotePayload.TYPE, UpdateJournalLogNotePayload.STREAM_CODEC,
                    JournalLogHandler::handleUpdateNote),
            new C2S<>(DeleteJournalLogPayload.TYPE, DeleteJournalLogPayload.STREAM_CODEC,
                    JournalLogHandler::handleDeleteLogs),
            new C2S<>(UpdateJournalLogRetentionPayload.TYPE, UpdateJournalLogRetentionPayload.STREAM_CODEC,
                    JournalLogHandler::handleUpdateRetention),
            new C2S<>(CatDeterrenceTogglePayload.TYPE, CatDeterrenceTogglePayload.STREAM_CODEC,
                    (player, payload) -> CatNetworkHandler.handleDeterrenceToggle(player)),
            new C2S<>(CatLightStepTogglePayload.TYPE, CatLightStepTogglePayload.STREAM_CODEC,
                    (player, payload) -> CatNetworkHandler.handleLightStepToggle(player)),
            new C2S<>(RequestScenarioCachePayload.TYPE, RequestScenarioCachePayload.STREAM_CODEC,
                    ScenarioSimulationHandler::handleCache),
            new C2S<>(RequestScenarioSimulationPayload.TYPE, RequestScenarioSimulationPayload.STREAM_CODEC,
                    ScenarioSimulationHandler::handleRequest)
    );

    /** 下行通道声明：类型与编解码由公共网络层持有，客户端接收绑定留在 client。 */
    public record S2C<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {

    }

    /** 公共 codec 注册与客户端绑定直接引用同一类型化声明。 */
    public static final S2C<SyncArchaeologyCatalogPayload> SYNC_ARCHAEOLOGY_CATALOG =
            new S2C<>(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC);
    public static final S2C<SyncCatalogHashPayload> SYNC_CATALOG_HASH =
            new S2C<>(SyncCatalogHashPayload.TYPE, SyncCatalogHashPayload.STREAM_CODEC);
    public static final S2C<SyncLootTableManagementPayload> SYNC_LOOT_TABLE_MANAGEMENT =
            new S2C<>(SyncLootTableManagementPayload.TYPE, SyncLootTableManagementPayload.STREAM_CODEC);
    public static final S2C<SyncJournalStatePayload> SYNC_JOURNAL_STATE =
            new S2C<>(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC);
    public static final S2C<SyncJournalStateIncrementalPayload> SYNC_JOURNAL_STATE_INCREMENTAL =
            new S2C<>(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC);
    public static final S2C<SyncJournalLogPayload> SYNC_JOURNAL_LOG =
            new S2C<>(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC);
    public static final S2C<SyncJournalLogSnapshotStartPayload> SYNC_JOURNAL_LOG_SNAPSHOT_START =
            new S2C<>(SyncJournalLogSnapshotStartPayload.TYPE, SyncJournalLogSnapshotStartPayload.STREAM_CODEC);
    public static final S2C<SyncJournalLogTableChunkPayload> SYNC_JOURNAL_LOG_TABLE_CHUNK =
            new S2C<>(SyncJournalLogTableChunkPayload.TYPE, SyncJournalLogTableChunkPayload.STREAM_CODEC);
    public static final S2C<SyncJournalLogSnapshotEndPayload> SYNC_JOURNAL_LOG_SNAPSHOT_END =
            new S2C<>(SyncJournalLogSnapshotEndPayload.TYPE, SyncJournalLogSnapshotEndPayload.STREAM_CODEC);
    public static final S2C<JournalLogDeleteResultPayload> JOURNAL_LOG_DELETE_RESULT =
            new S2C<>(JournalLogDeleteResultPayload.TYPE, JournalLogDeleteResultPayload.STREAM_CODEC);
    public static final S2C<SyncCatFavorPayload> SYNC_CAT_FAVOR =
            new S2C<>(SyncCatFavorPayload.TYPE, SyncCatFavorPayload.STREAM_CODEC);
    public static final S2C<SyncReaderScanResultPayload> SYNC_READER_SCAN_RESULT =
            new S2C<>(SyncReaderScanResultPayload.TYPE, SyncReaderScanResultPayload.STREAM_CODEC);
    public static final S2C<SyncEnchantmentRevealListPayload> SYNC_ENCHANTMENT_REVEAL_LIST =
            new S2C<>(SyncEnchantmentRevealListPayload.TYPE, SyncEnchantmentRevealListPayload.STREAM_CODEC);
    public static final S2C<NotifyTableCompletionRewardPayload> NOTIFY_TABLE_COMPLETION_REWARD =
            new S2C<>(NotifyTableCompletionRewardPayload.TYPE, NotifyTableCompletionRewardPayload.STREAM_CODEC);
    public static final S2C<SyncScenarioCachePayload> SYNC_SCENARIO_CACHE =
            new S2C<>(SyncScenarioCachePayload.TYPE, SyncScenarioCachePayload.STREAM_CODEC);
    public static final S2C<SyncScenarioResultPayload> SYNC_SCENARIO_RESULT =
            new S2C<>(SyncScenarioResultPayload.TYPE, SyncScenarioResultPayload.STREAM_CODEC);
    public static final S2C<ScenarioRequestRejectedPayload> SCENARIO_REQUEST_REJECTED =
            new S2C<>(ScenarioRequestRejectedPayload.TYPE, ScenarioRequestRejectedPayload.STREAM_CODEC);
    public static final S2C<SyncSimulationAssistPayload> SYNC_SIMULATION_ASSIST =
            new S2C<>(SyncSimulationAssistPayload.TYPE, SyncSimulationAssistPayload.STREAM_CODEC);

    public static final List<S2C<?>> S2C_PAYLOADS = List.of(
            SYNC_ARCHAEOLOGY_CATALOG,
            SYNC_CATALOG_HASH,
            SYNC_LOOT_TABLE_MANAGEMENT,
            SYNC_JOURNAL_STATE,
            SYNC_JOURNAL_STATE_INCREMENTAL,
            SYNC_JOURNAL_LOG,
            SYNC_JOURNAL_LOG_SNAPSHOT_START,
            SYNC_JOURNAL_LOG_TABLE_CHUNK,
            SYNC_JOURNAL_LOG_SNAPSHOT_END,
            JOURNAL_LOG_DELETE_RESULT,
            SYNC_CAT_FAVOR,
            SYNC_READER_SCAN_RESULT,
            SYNC_ENCHANTMENT_REVEAL_LIST,
            NOTIFY_TABLE_COMPLETION_REWARD,
            SYNC_SCENARIO_CACHE,
            SYNC_SCENARIO_RESULT,
            SCENARIO_REQUEST_REJECTED,
            SYNC_SIMULATION_ASSIST
    );

    static {
        checkUniqueChannels("C2S", C2S_PAYLOADS.stream().map(payload -> payload.type().id()).toList());
        checkUniqueChannels("S2C", S2C_PAYLOADS.stream().map(payload -> payload.type().id()).toList());
    }

    private static void checkUniqueChannels(String direction, List<ResourceLocation> channels) {
        Set<ResourceLocation> seen = new HashSet<>();
        for (ResourceLocation channel : channels) {
            if (!seen.add(channel)) {
                throw new IllegalStateException("Duplicate " + direction + " payload channel: " + channel);
            }
        }
    }

}
