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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import java.util.function.BiConsumer;

/** 公共网络声明：统一维护类型、codec 与基于接收契约的分发函数。 */
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

    /** 下行通道声明，分发函数只引用中立接收契约。 */
    public record S2C<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
            BiConsumer<ClientPayloadReceiver, T> handler) {
        private S2CSpec<T> serverSpec() {
            return new S2CSpec<>(type, streamCodec);
        }
    }

    /** 同一声明供公共 codec 注册和客户端处理绑定使用。 */
    public static final List<S2C<?>> S2C_PAYLOADS = List.of(
            new S2C<>(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncCatalogHashPayload.TYPE, SyncCatalogHashPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncLootTableManagementPayload.TYPE, SyncLootTableManagementPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncJournalLogSnapshotStartPayload.TYPE, SyncJournalLogSnapshotStartPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncJournalLogTableChunkPayload.TYPE, SyncJournalLogTableChunkPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncJournalLogSnapshotEndPayload.TYPE, SyncJournalLogSnapshotEndPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(JournalLogDeleteResultPayload.TYPE, JournalLogDeleteResultPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncCatFavorPayload.TYPE, SyncCatFavorPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncReaderScanResultPayload.TYPE, SyncReaderScanResultPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncEnchantmentRevealListPayload.TYPE, SyncEnchantmentRevealListPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(NotifyTableCompletionRewardPayload.TYPE, NotifyTableCompletionRewardPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncScenarioCachePayload.TYPE, SyncScenarioCachePayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncScenarioResultPayload.TYPE, SyncScenarioResultPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(ScenarioRequestRejectedPayload.TYPE, ScenarioRequestRejectedPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive),
            new S2C<>(SyncSimulationAssistPayload.TYPE, SyncSimulationAssistPayload.STREAM_CODEC,
                    ClientPayloadReceiver::receive)
    );

    public static final List<S2CSpec<?>> S2C_SPECS = serverSpecs();

    static {
        checkUniqueChannels("C2S", C2S_PAYLOADS.stream().map(payload -> payload.type().id()).toList());
        checkUniqueChannels("S2C", S2C_PAYLOADS.stream().map(payload -> payload.type().id()).toList());
    }

    private static List<S2CSpec<?>> serverSpecs() {
        List<S2CSpec<?>> specs = new ArrayList<>();
        for (S2C<?> declaration : S2C_PAYLOADS) {
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

}
