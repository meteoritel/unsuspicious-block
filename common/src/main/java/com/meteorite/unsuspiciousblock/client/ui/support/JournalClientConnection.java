package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload.Operation;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload.Result;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.DeleteJournalLogPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.JournalLogNoteEditScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.JournalLogRetentionScreen;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 连接归属、加载、接包提交与持久化的唯一推进宿主。
 */
public final class JournalClientConnection {
    private static final Queue<Inbound> incoming = new ConcurrentLinkedQueue<>();
    private static volatile @Nullable Session current;
    private static long epoch;
    private static Consumer<Component> editNotifier = message -> {};
    private static final JournalMirror OFFLINE_LOG = new JournalMirror();
    private static ScenarioSession scenario = new ScenarioSession(ClientNetwork::sendToServer, System::currentTimeMillis,
                ArchaeologyJournalClientState::refreshCatalog);

    /**
     * 当前连接唯一的场景动作与快照入口。
     */
    public static ScenarioSession scenario() {
        return scenario;
    }

    private static final List<Runnable> connectionResets = new ArrayList<>();

    private record Inbound(ClientPacketListener source, long epoch, Runnable apply) {
    }

    private static final class Session {
        private final ClientPacketListener source;
        private final long epoch;
        private final JournalMirror mirror;
        private final boolean supported;
        private boolean loaded;
        private @Nullable PendingEdit edit;

        private Session(ClientPacketListener source, long epoch) {
            this.source = source;
            this.epoch = epoch;
            this.supported = ClientNetwork.supportsJournal();
            this.mirror = new JournalMirror();
            if (!supported) this.mirror.unavailable();
        }
    }

    private static final class PendingEdit {
        private final UUID id;
        private final Operation operation;
        private final @Nullable ResourceLocation table;
        private final Consumer<JournalLogEditResultPayload> complete;
        private final long deadline = System.currentTimeMillis() + JournalTransferAssembly.RESPONSE_TIMEOUT_MS;
        private @Nullable JournalLogEditResultPayload result;

        private PendingEdit(UUID id, Operation operation, @Nullable ResourceLocation table,
                            Consumer<JournalLogEditResultPayload> complete) {
            this.id = id;
            this.operation = operation;
            this.table = table;
            this.complete = complete;
        }
    }

    /** 跟踪当前编辑请求，已应用回执等待对应日志修订提交。 */
    public static boolean expectEdit(UUID id, Operation operation, @Nullable ResourceLocation table,
                                     Consumer<JournalLogEditResultPayload> complete) {
        Session session = current;
        if (session == null || session.edit != null) return false;
        session.edit = new PendingEdit(id, operation, table, complete);
        return true;
    }

    /** 当前草稿关闭后丢弃专属于该草稿的回执。 */
    public static void abandonEdit(@Nullable UUID id) {
        Session session = current;
        if (session != null && session.edit != null && session.edit.id.equals(id)) session.edit = null;
    }

    /** 删除确认将同一身份的所有片交给服务端受理。 */
    public static boolean delete(List<DeleteJournalLogPayload> pieces) {
        DeleteJournalLogPayload first = pieces.getFirst();
        if (!expectEdit(first.requestId(), Operation.DELETE, first.tableId(), result -> editNotifier.accept(
                result.result() == Result.APPLIED
                        ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.success", result.removedCount())
                        : editMessage(result)))) return false;
        pieces.forEach(ClientNetwork::sendToServer);
        return true;
    }

    public static void registerLogEditNotifier(Consumer<Component> notifier) {
        editNotifier = notifier;
    }

    /** 只将回执交给身份、操作和表均匹配的编辑入口。 */
    public static void receiveEditResult(JournalLogEditResultPayload result) {
        Session session = current;
        PendingEdit pending = session == null ? null : session.edit;
        if (pending != null && pending.id.equals(result.requestId()) && pending.operation == result.operation()
                && Objects.equals(pending.table, result.tableId())) {
            pending.result = result;
        }
    }

    public static Component editMessage(JournalLogEditResultPayload result) {
        return switch (result.result()) {
            case APPLIED, UNCHANGED -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_edit_unchanged");
            case NOT_FOUND -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.not_found");
            case INVALID -> result.operation() == Operation.RETENTION || result.operation() == Operation.PRUNE
                    ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_retention.invalid_range", result.globalLimit())
                    : Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.invalid");
            case SAVE_FAILED -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_edit_save_failed");
            case EXPIRED -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_edit_expired");
        };
    }

    private static void tickEdit(Session session) {
        PendingEdit pending = session.edit;
        if (pending == null) return;
        JournalLogEditResultPayload result = pending.result;
        if (result == null || result.revision() > session.mirror.committed()) {
            if (System.currentTimeMillis() < pending.deadline) return;
            result = new JournalLogEditResultPayload(pending.id, pending.operation, pending.table,
                    Result.EXPIRED, 0, 0, 0, session.mirror.totals().limit());
        }
        session.edit = null;
        pending.complete.accept(result);
    }

    private JournalClientConnection() {
    }

    /**
     * 安装连接作用域复位动作，连接切换与断连共用同一序列。
     */
    public static void registerConnectionReset(Runnable reset) {
        connectionResets.add(reset);
    }

    // 接包只保存原连接与 epoch，应用和本地 I/O 统一在客户端 tick 执行。
    public static void receive(ClientPacketListener source, Runnable apply) {
        Session session = current;
        long receivedEpoch = session != null && session.source == source ? session.epoch : 0L;
        incoming.add(new Inbound(source, receivedEpoch, apply));
    }

    /** 当前连接的日志只读投影及历史查询入口。 */
    public static JournalMirror log() {
        Session session = current;
        return session == null ? OFFLINE_LOG : session.mirror;
    }

    public static void receiveTransfer(JournalTransferPayload piece) {
        switch (piece.kind().stream()) {
            case LOG -> log().receive(piece);
            case PROGRESS -> ArchaeologyJournalClientState.receiveProgress(piece);
            case CATALOG -> {
                Session session = current;
                if (session != null) ArchaeologyJournalClientState.receiveCatalogTransfer(piece, session.source.registryAccess());
            }
        }
    }

    /** Fabric 同连接协商缺少版本化通道时展示不可用状态。 */
    public static boolean supportsJournal() {
        Session session = current;
        return session == null ? ClientNetwork.supportsJournal() : session.supported;
    }

    public static long epoch() {
        return epoch;
    }

    public static void tick() {
        ClientPacketListener source = Minecraft.getInstance().getConnection();
        Session session = current;
        if (source == null) {
            if (session != null) {
                disconnect();
            }
            JournalUiPreferencesStore.flushIfDirty();
            return;
        }
        if (session == null || session.source != source) {
            session = new Session(source, ++epoch);
            JournalUiPreferencesStore.beginConnection();
            resetConnectionStates();
            current = session;
        }
        if (!session.loaded) {
            Path preferencesPath = JournalLocalPersistence.resolveCurrentPath(JournalUiPreferencesStore.STORAGE_FILE);
            if (preferencesPath != null) {
                JournalUiPreferencesStore.loadCurrentPath(preferencesPath);
                session.loaded = true;
            }
        }
        Inbound message;
        while ((message = incoming.poll()) != null) {
            if (message.source() == session.source
                    && (message.epoch() == 0L || message.epoch() == session.epoch)) {
                message.apply().run();
            }
        }
        JournalClientConnection.scenario().tick();
        var screen = Minecraft.getInstance().screen;
        if (!(screen instanceof ArchaeologyJournalScreen || screen instanceof JournalLogNoteEditScreen
                || screen instanceof JournalLogRetentionScreen)) {
            session.mirror.select(null);
        }
        if (session.supported) {
            session.mirror.tick();
            ArchaeologyJournalClientState.tickProgress();
            ArchaeologyJournalClientState.tickCatalog();
            tickEdit(session);
        }
        JournalUiPreferencesStore.tick();
    }

    public static void disconnect() {
        JournalUiPreferencesStore.beginConnection();
        current = null;
        epoch++;
        incoming.clear();
        resetConnectionStates();
    }

    private static void resetConnectionStates() {
        ArchaeologyJournalClientState.resetConnectionMemory();
        scenario.retire();
        scenario = new ScenarioSession(ClientNetwork::sendToServer, System::currentTimeMillis,
                ArchaeologyJournalClientState::refreshCatalog);
        for (Runnable reset : connectionResets) {
            reset.run();
        }
    }
}
