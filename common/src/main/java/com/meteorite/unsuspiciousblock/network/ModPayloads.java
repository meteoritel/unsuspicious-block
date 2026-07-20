package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.cat.CatNetworkHandler;
import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHighlightState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import com.meteorite.unsuspiciousblock.network.journal.ReaderScanLevelHandler;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatDeterrenceTogglePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatLightStepTogglePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalStateFullPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.SpecimenBoxScrollPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UploadJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxQuickInteraction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 统一管理所有自定义网络包（payload）的注册信息。
 * <p>
 * 平台层（NeoForge / Fabric）仅遍历 {@link #C2S_PAYLOADS}、{@link #S2C_SPECS} 与
 * {@link Client#S2C_PAYLOADS} 完成类型注册与接收器绑定，避免在每个平台入口手写重复的注册代码。
 * <p>
 * 客户端/服务端分离约束：S2C 的 handler 引用客户端状态类，因此放在 {@link Client} 嵌套类中。
 * 服务端不会加载该嵌套类（JVM 按需加载嵌套类），从而避免服务端 classpath 引入客户端类。
 */
public final class ModPayloads {
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
            new C2S<>(UploadJournalLogSnapshotPayload.TYPE, UploadJournalLogSnapshotPayload.STREAM_CODEC,
                    JournalLogHandler::handleUploadedLogSnapshot),
            new C2S<>(UpdateReaderScanLevelPayload.TYPE, UpdateReaderScanLevelPayload.STREAM_CODEC,
                    (player, payload) -> ReaderScanLevelHandler.handleUpdateReaderScanLevel(payload, player)),
            new C2S<>(RequestCatalogPayload.TYPE, RequestCatalogPayload.STREAM_CODEC,
                    (player, payload) -> JournalCatalogHandler.handleRequestCatalog(player)),
            new C2S<>(RequestJournalStateFullPayload.TYPE, RequestJournalStateFullPayload.STREAM_CODEC,
                    (player, payload) -> JournalStateHandler.handleRequestFull(player)),
            new C2S<>(RequestJournalLogSnapshotPayload.TYPE, RequestJournalLogSnapshotPayload.STREAM_CODEC,
                    (player, payload) -> JournalLogHandler.handleRequestSnapshot(player)),
            new C2S<>(UpdateJournalLogNotePayload.TYPE, UpdateJournalLogNotePayload.STREAM_CODEC,
                    JournalLogHandler::handleUpdateNote),
            new C2S<>(CatDeterrenceTogglePayload.TYPE, CatDeterrenceTogglePayload.STREAM_CODEC,
                    (player, payload) -> CatNetworkHandler.handleDeterrenceToggle(player)),
            new C2S<>(CatLightStepTogglePayload.TYPE, CatLightStepTogglePayload.STREAM_CODEC,
                    (player, payload) -> CatNetworkHandler.handleLightStepToggle(player)),
            new C2S<>(SpecimenBoxScrollPayload.TYPE, SpecimenBoxScrollPayload.STREAM_CODEC,
                    (player, payload) -> SpecimenBoxQuickInteraction.handleScroll(
                            player, payload.selectedInnerSlot()))
    );

    /** S2C payload 类型列表（仅 type + streamCodec，供服务端注册编解码器，Fabric 需要） */
    public static final List<S2CSpec<?>> S2C_SPECS = List.of(
            new S2CSpec<>(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC),
            new S2CSpec<>(SyncCatalogHashPayload.TYPE, SyncCatalogHashPayload.STREAM_CODEC),
            new S2CSpec<>(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC),
            new S2CSpec<>(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC),
            new S2CSpec<>(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC),
            new S2CSpec<>(SyncJournalLogSnapshotPayload.TYPE, SyncJournalLogSnapshotPayload.STREAM_CODEC),
            new S2CSpec<>(SyncCatFavorPayload.TYPE, SyncCatFavorPayload.STREAM_CODEC),
            new S2CSpec<>(SyncReaderScanResultPayload.TYPE, SyncReaderScanResultPayload.STREAM_CODEC),
            new S2CSpec<>(SyncEnchantmentRevealListPayload.TYPE, SyncEnchantmentRevealListPayload.STREAM_CODEC),
            new S2CSpec<>(NotifyTableCompletionRewardPayload.TYPE, NotifyTableCompletionRewardPayload.STREAM_CODEC)
    );

    /**
     * 客户端专属：S2C payload 完整列表（含 handler）。
     * <p>
     * 单独放在嵌套类中，服务端不会加载此类，从而避免服务端 classpath 引入客户端状态类。
     * 平台客户端入口遍历此列表注册 S2C 接收器。
     */
    public static final class Client {
        private Client() {
        }

        /** S2C payload 完整描述：类型 + 编解码 + 客户端处理函数 */
        public record S2C<T extends CustomPacketPayload>(
                CustomPacketPayload.Type<T> type,
                StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
                Consumer<T> handler) {
        }

        public static final List<S2C<?>> S2C_PAYLOADS = List.of(
                new S2C<>(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveCatalog),
                new S2C<>(SyncCatalogHashPayload.TYPE, SyncCatalogHashPayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveCatalogHash),
                new S2C<>(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveState),
                new S2C<>(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveStateIncremental),
                new S2C<>(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveLogUpdate),
                new S2C<>(SyncJournalLogSnapshotPayload.TYPE, SyncJournalLogSnapshotPayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveLogSnapshot),
                new S2C<>(SyncCatFavorPayload.TYPE, SyncCatFavorPayload.STREAM_CODEC,
                        HandOfCatClientState::receive),
                new S2C<>(SyncReaderScanResultPayload.TYPE, SyncReaderScanResultPayload.STREAM_CODEC,
                        payload -> ReaderScanHighlightState.receive(payload.suspiciousBlocks(), payload.lootContainers())),
                new S2C<>(SyncEnchantmentRevealListPayload.TYPE, SyncEnchantmentRevealListPayload.STREAM_CODEC,
                        EnchantmentRevealClientState::receive),
                new S2C<>(NotifyTableCompletionRewardPayload.TYPE, NotifyTableCompletionRewardPayload.STREAM_CODEC,
                        ArchaeologyJournalClientState::receiveTableCompletionReward)
        );
    }
}
