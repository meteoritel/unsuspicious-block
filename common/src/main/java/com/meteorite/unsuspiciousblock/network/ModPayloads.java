package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestScenarioCachePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncScenarioCachePayload;

import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.network.journal.SimulationAssistHandler;
import com.meteorite.unsuspiciousblock.cat.CatNetworkHandler;
import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.LootTableManagementHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import com.meteorite.unsuspiciousblock.network.journal.ReaderScanLevelHandler;
import com.meteorite.unsuspiciousblock.network.journal.ScenarioSimulationHandler;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatDeterrenceTogglePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatLightStepTogglePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalStateFullPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestScenarioSimulationPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogTableChunkPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.JournalLogDeleteResultPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 统一管理所有自定义网络包（payload）的注册信息。
 * <p>
 * 平台层（NeoForge / Fabric）仅遍历 {@link #C2S_PAYLOADS}、{@link #S2C_SPECS} 与
 * {@link Client#S2C_PAYLOADS} 完成类型注册与接收器绑定，避免在每个平台入口手写重复的注册代码。
 * <p>
 * 客户端/服务端分离约束：S2C 的 handler 引用客户端状态类，仅在客户端绑定时加载。
 * 服务端只派生 codec 视图，不执行工厂；客户端状态引用集中在 ClientHandlers 嵌套类。
 */
public final class ModPayloads {
    public static final String PROTOCOL_VERSION = "4.10";

    private ModPayloads() {
    }

    /** C2S payload 描述：类型 + 编解码 + 服务端处理函数 */
    public record C2S<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
            BiConsumer<ServerPlayer, T> handler) {
    }

    /** S2C payload 类型描述：类型 + 编解码（不含 handler，供服务端注册编解码器） */
    public record S2CSpec<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {
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

    private record S2CDeclaration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
            Supplier<Consumer<T>> clientHandler) {
        private S2CSpec<T> serverSpec() {
            return new S2CSpec<>(type, streamCodec);
        }
    }

    // 工厂在客户端绑定时才访问 ClientHandlers，专用端只派生编解码视图。
    private static final List<S2CDeclaration<?>> S2C_DECLARATIONS = List.of(
            new S2CDeclaration<>(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_ARCHAEOLOGY_CATALOG),
            new S2CDeclaration<>(SyncCatalogHashPayload.TYPE, SyncCatalogHashPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_CATALOG_HASH),
            new S2CDeclaration<>(SyncLootTableManagementPayload.TYPE, SyncLootTableManagementPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_LOOT_TABLE_MANAGEMENT),
            new S2CDeclaration<>(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_JOURNAL_STATE),
            new S2CDeclaration<>(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_JOURNAL_STATE_INCREMENTAL),
            new S2CDeclaration<>(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_JOURNAL_LOG),
            new S2CDeclaration<>(SyncJournalLogSnapshotStartPayload.TYPE, SyncJournalLogSnapshotStartPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_JOURNAL_LOG_SNAPSHOT_START),
            new S2CDeclaration<>(SyncJournalLogTableChunkPayload.TYPE, SyncJournalLogTableChunkPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_JOURNAL_LOG_TABLE_CHUNK),
            new S2CDeclaration<>(SyncJournalLogSnapshotEndPayload.TYPE, SyncJournalLogSnapshotEndPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_JOURNAL_LOG_SNAPSHOT_END),
            new S2CDeclaration<>(JournalLogDeleteResultPayload.TYPE, JournalLogDeleteResultPayload.STREAM_CODEC,
                    () -> ClientHandlers.JOURNAL_LOG_DELETE_RESULT),
            new S2CDeclaration<>(SyncCatFavorPayload.TYPE, SyncCatFavorPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_CAT_FAVOR),
            new S2CDeclaration<>(SyncReaderScanResultPayload.TYPE, SyncReaderScanResultPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_READER_SCAN_RESULT),
            new S2CDeclaration<>(SyncEnchantmentRevealListPayload.TYPE, SyncEnchantmentRevealListPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_ENCHANTMENT_REVEAL_LIST),
            new S2CDeclaration<>(NotifyTableCompletionRewardPayload.TYPE, NotifyTableCompletionRewardPayload.STREAM_CODEC,
                    () -> ClientHandlers.NOTIFY_TABLE_COMPLETION_REWARD),
            new S2CDeclaration<>(SyncScenarioCachePayload.TYPE, SyncScenarioCachePayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_SCENARIO_CACHE),
            new S2CDeclaration<>(SyncScenarioResultPayload.TYPE, SyncScenarioResultPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_SCENARIO_RESULT),
            new S2CDeclaration<>(ScenarioRequestRejectedPayload.TYPE, ScenarioRequestRejectedPayload.STREAM_CODEC,
                    () -> ClientHandlers.SCENARIO_REQUEST_REJECTED),
            new S2CDeclaration<>(SyncSimulationAssistPayload.TYPE, SyncSimulationAssistPayload.STREAM_CODEC,
                    () -> ClientHandlers.SYNC_SIMULATION_ASSIST)
    );

    public static final List<S2CSpec<?>> S2C_SPECS = serverSpecs();

    static {
        checkUniqueChannels("C2S", C2S_PAYLOADS.stream().map(payload -> payload.type().id()).toList());
        checkUniqueChannels("S2C", S2C_DECLARATIONS.stream().map(payload -> payload.type().id()).toList());
    }

    private static List<S2CSpec<?>> serverSpecs() {
        List<S2CSpec<?>> specs = new ArrayList<>();
        for (S2CDeclaration<?> declaration : S2C_DECLARATIONS) {
            specs.add(declaration.serverSpec());
        }
        return List.copyOf(specs);
    }

    private static void checkUniqueChannels(String direction, List<ResourceLocation> channels) {
        Set<ResourceLocation> seen = new HashSet<>();
        for (ResourceLocation channel : channels) {
            if (!seen.add(channel)) {
                throw new IllegalStateException("Duplicate " + direction + " payload channel: " + channel);
            }
        }
    }

    public static final class Client {
        private Client() {
        }

        public record S2C<T extends CustomPacketPayload>(
                CustomPacketPayload.Type<T> type,
                StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
                Consumer<T> handler) {
        }

        public static final List<S2C<?>> S2C_PAYLOADS = clientBindings();

        private static List<S2C<?>> clientBindings() {
            List<S2C<?>> bindings = new ArrayList<>();
            for (S2CDeclaration<?> declaration : S2C_DECLARATIONS) {
                bindings.add(bind(declaration));
            }
            return List.copyOf(bindings);
        }

        private static <T extends CustomPacketPayload> S2C<T> bind(S2CDeclaration<T> declaration) {
            Consumer<T> handler = Objects.requireNonNull(declaration.clientHandler().get(),
                    "Missing client handler for " + declaration.type().id());
            return new S2C<>(declaration.type(), declaration.streamCodec(), handler);
        }
    }

    private static final class ClientHandlers {
        private static final Consumer<SyncArchaeologyCatalogPayload> SYNC_ARCHAEOLOGY_CATALOG =
                ArchaeologyJournalClientState::receiveCatalog;
        private static final Consumer<SyncCatalogHashPayload> SYNC_CATALOG_HASH =
                ArchaeologyJournalClientState::receiveCatalogHash;
        private static final Consumer<SyncLootTableManagementPayload> SYNC_LOOT_TABLE_MANAGEMENT =
                LootTableManagementClientState::receive;
        private static final Consumer<SyncJournalStatePayload> SYNC_JOURNAL_STATE =
                ArchaeologyJournalClientState::receiveState;
        private static final Consumer<SyncJournalStateIncrementalPayload> SYNC_JOURNAL_STATE_INCREMENTAL =
                ArchaeologyJournalClientState::receiveStateIncremental;
        private static final Consumer<SyncJournalLogPayload> SYNC_JOURNAL_LOG =
                ArchaeologyJournalClientState::receiveLogUpdate;
        private static final Consumer<SyncJournalLogSnapshotStartPayload> SYNC_JOURNAL_LOG_SNAPSHOT_START =
                ArchaeologyJournalClientState::beginLogSnapshot;
        private static final Consumer<SyncJournalLogTableChunkPayload> SYNC_JOURNAL_LOG_TABLE_CHUNK =
                ArchaeologyJournalClientState::receiveLogSnapshotChunk;
        private static final Consumer<SyncJournalLogSnapshotEndPayload> SYNC_JOURNAL_LOG_SNAPSHOT_END =
                ArchaeologyJournalClientState::completeLogSnapshot;
        private static final Consumer<JournalLogDeleteResultPayload> JOURNAL_LOG_DELETE_RESULT =
                ArchaeologyJournalClientState::receiveLogDeleteResult;
        private static final Consumer<SyncCatFavorPayload> SYNC_CAT_FAVOR =
                HandOfCatClientState::receive;
        private static final Consumer<SyncReaderScanResultPayload> SYNC_READER_SCAN_RESULT =
                ReaderScanHudState::receive;
        private static final Consumer<SyncEnchantmentRevealListPayload> SYNC_ENCHANTMENT_REVEAL_LIST =
                EnchantmentRevealClientState::receive;
        private static final Consumer<NotifyTableCompletionRewardPayload> NOTIFY_TABLE_COMPLETION_REWARD =
                ArchaeologyJournalClientState::receiveTableCompletionReward;
        private static final Consumer<SyncScenarioCachePayload> SYNC_SCENARIO_CACHE =
                ScenarioSimulationClientState::receiveCache;
        private static final Consumer<SyncScenarioResultPayload> SYNC_SCENARIO_RESULT =
                ScenarioSimulationClientState::receive;
        private static final Consumer<ScenarioRequestRejectedPayload> SCENARIO_REQUEST_REJECTED =
                ScenarioSimulationClientState::receiveRejection;
        private static final Consumer<SyncSimulationAssistPayload> SYNC_SIMULATION_ASSIST =
                ScenarioSimulationClientState::receiveAssist;

        private ClientHandlers() {
        }
    }
}
