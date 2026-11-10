package com.meteorite.unsuspiciousblock.client.network;

import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalLogLocalStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.network.ModPayloads;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogDeleteResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.NotifyTableCompletionRewardPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
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
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncSimulationAssistPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 客户端 S2C 类型到状态所有者的唯一绑定清单。 */
public final class ClientPayloadBindings {
    private static final List<Binding<?>> BINDINGS = List.of(
            bind(SyncArchaeologyCatalogPayload.TYPE, ArchaeologyJournalClientState::receiveCatalog),
            bind(SyncCatalogHashPayload.TYPE, ArchaeologyJournalClientState::receiveCatalogHash),
            bind(SyncLootTableManagementPayload.TYPE, LootTableManagementClientState::receive),
            bind(SyncJournalStatePayload.TYPE, ArchaeologyJournalClientState::receiveState),
            bind(SyncJournalStateIncrementalPayload.TYPE, ArchaeologyJournalClientState::receiveStateIncremental),
            bind(SyncJournalLogPayload.TYPE, ArchaeologyJournalLogLocalStore::applyUpdate),
            bind(SyncJournalLogSnapshotStartPayload.TYPE, ArchaeologyJournalLogLocalStore::beginSnapshot),
            bind(SyncJournalLogTableChunkPayload.TYPE, ArchaeologyJournalLogLocalStore::applySnapshotChunk),
            bind(SyncJournalLogSnapshotEndPayload.TYPE, ArchaeologyJournalLogLocalStore::completeSnapshot),
            bind(JournalLogDeleteResultPayload.TYPE, ArchaeologyJournalClientState::receiveLogDeleteResult),
            bind(SyncCatFavorPayload.TYPE, HandOfCatClientState::receive),
            bind(SyncReaderScanResultPayload.TYPE, ReaderScanHudState::receive),
            bind(SyncEnchantmentRevealListPayload.TYPE, EnchantmentRevealClientState::receive),
            bind(NotifyTableCompletionRewardPayload.TYPE, ArchaeologyJournalClientState::receiveTableCompletionReward),
            bind(SyncScenarioCachePayload.TYPE, ScenarioSimulationClientState::receiveCache),
            bind(SyncScenarioResultPayload.TYPE, ScenarioSimulationClientState::receive),
            bind(ScenarioRequestRejectedPayload.TYPE, ScenarioSimulationClientState::receiveRejection),
            bind(SyncSimulationAssistPayload.TYPE, ScenarioSimulationClientState::receiveAssist)
    );

    static {
        Set<ResourceLocation> bound = new HashSet<>();
        for (Binding<?> binding : BINDINGS) {
            if (!bound.add(binding.type().id())) {
                throw new IllegalStateException("Duplicate client payload binding: " + binding.type().id());
            }
        }
        Set<ResourceLocation> declared = new HashSet<>();
        for (ModPayloads.S2C<?> declaration : ModPayloads.S2C_PAYLOADS) {
            declared.add(declaration.type().id());
        }
        if (!bound.equals(declared)) {
            throw new IllegalStateException("Client payload binding coverage does not match declarations");
        }
    }

    private ClientPayloadBindings() {
    }

    /** 返回覆盖公共 S2C 声明的只读接收绑定。 */
    public static List<Binding<?>> all() {
        return BINDINGS;
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> Binding<T> bind(
            CustomPacketPayload.Type<T> type, Consumer<T> receiver) {
        for (ModPayloads.S2C<?> declaration : ModPayloads.S2C_PAYLOADS) {
            if (declaration.type().equals(type)) {
                // 通道类型是公共声明与接收函数的共同键，泛型擦除仅在查表处恢复。
                ModPayloads.S2C<T> typedDeclaration = (ModPayloads.S2C<T>) declaration;
                return new Binding<>(type, typedDeclaration.streamCodec(), receiver);
            }
        }
        throw new IllegalStateException("Undeclared client payload type: " + type.id());
    }

    /** 同一 payload 类型的 codec 与客户端状态接收函数。 */
    public record Binding<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
            Consumer<T> receiver) {
    }
}
