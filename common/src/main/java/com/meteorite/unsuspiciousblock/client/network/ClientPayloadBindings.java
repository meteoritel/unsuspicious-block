package com.meteorite.unsuspiciousblock.client.network;

import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalLogLocalStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.network.ModPayloads;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 客户端 S2C 类型到状态所有者的唯一绑定清单。 */
public final class ClientPayloadBindings {
    private static final List<Binding<?>> BINDINGS = List.of(
            bind(ModPayloads.SYNC_ARCHAEOLOGY_CATALOG, ArchaeologyJournalClientState::receiveCatalog),
            bind(ModPayloads.SYNC_CATALOG_HASH, ArchaeologyJournalClientState::receiveCatalogHash),
            bind(ModPayloads.SYNC_LOOT_TABLE_MANAGEMENT, LootTableManagementClientState::receive),
            bind(ModPayloads.SYNC_JOURNAL_STATE, ArchaeologyJournalClientState::receiveState),
            bind(ModPayloads.SYNC_JOURNAL_STATE_INCREMENTAL, ArchaeologyJournalClientState::receiveStateIncremental),
            bind(ModPayloads.SYNC_JOURNAL_LOG, ArchaeologyJournalLogLocalStore::applyUpdate),
            bind(ModPayloads.SYNC_JOURNAL_LOG_SNAPSHOT_START, ArchaeologyJournalLogLocalStore::beginSnapshot),
            bind(ModPayloads.SYNC_JOURNAL_LOG_TABLE_CHUNK, ArchaeologyJournalLogLocalStore::applySnapshotChunk),
            bind(ModPayloads.SYNC_JOURNAL_LOG_SNAPSHOT_END, ArchaeologyJournalLogLocalStore::completeSnapshot),
            bind(ModPayloads.JOURNAL_LOG_DELETE_RESULT, ArchaeologyJournalClientState::receiveLogDeleteResult),
            bind(ModPayloads.SYNC_CAT_FAVOR, HandOfCatClientState::receive),
            bind(ModPayloads.SYNC_READER_SCAN_RESULT, ReaderScanHudState::receive),
            bind(ModPayloads.SYNC_ENCHANTMENT_REVEAL_LIST, EnchantmentRevealClientState::receive),
            bind(ModPayloads.NOTIFY_TABLE_COMPLETION_REWARD, ArchaeologyJournalClientState::receiveTableCompletionReward),
            bind(ModPayloads.SYNC_SCENARIO_CACHE, ScenarioSimulationClientState::receiveCache),
            bind(ModPayloads.SYNC_SCENARIO_RESULT, ScenarioSimulationClientState::receive),
            bind(ModPayloads.SCENARIO_REQUEST_REJECTED, ScenarioSimulationClientState::receiveRejection),
            bind(ModPayloads.SYNC_SIMULATION_ASSIST, ScenarioSimulationClientState::receiveAssist)
    );

    static {
        Set<ResourceLocation> bound = new HashSet<>();
        for (Binding<?> binding : BINDINGS) {
            if (!bound.add(binding.declaration().type().id())) {
                throw new IllegalStateException("Duplicate client payload binding: " + binding.declaration().type().id());
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

    private static <T extends CustomPacketPayload> Binding<T> bind(
            ModPayloads.S2C<T> declaration, Consumer<T> receiver) {
        return new Binding<>(declaration, receiver);
    }

    /** 同一 payload 类型的 codec 与客户端状态接收函数。 */
    public record Binding<T extends CustomPacketPayload>(
            ModPayloads.S2C<T> declaration,
            Consumer<T> receiver) {
    }
}
