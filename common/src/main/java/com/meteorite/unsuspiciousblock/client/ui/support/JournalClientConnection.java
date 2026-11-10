package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 连接归属、加载、接包提交与持久化的唯一推进宿主。 */
public final class JournalClientConnection {
    private static final Queue<Inbound> incoming = new ConcurrentLinkedQueue<>();
    private static volatile @Nullable Session current;
    private static long epoch;
    private static final List<Runnable> connectionResets = new ArrayList<>();

    private record Inbound(ClientPacketListener source, long epoch, Runnable apply) {
    }

    private static final class Session {
        private final ClientPacketListener source;
        private final long epoch;
        private final JournalMirror mirror;
        private boolean loaded;

        private Session(ClientPacketListener source, long epoch) {
            this.source = source;
            this.epoch = epoch;
            this.mirror = new JournalMirror(System::currentTimeMillis, () -> {
                if (current == this && Minecraft.getInstance().getConnection() == source) {
                    ClientNetwork.sendToServer(new RequestJournalLogSnapshotPayload());
                }
            });
        }
    }

    private JournalClientConnection() {
    }

    /** 安装连接作用域复位动作，连接切换与断连共用同一序列。 */
    public static void registerConnectionReset(Runnable reset) {
        connectionResets.add(reset);
    }

    // 接包只保存原连接与 epoch，应用和本地 I/O 统一在客户端 tick 执行。
    public static void receive(ClientPacketListener source, Runnable apply) {
        Session session = current;
        long receivedEpoch = session != null && session.source == source ? session.epoch : 0L;
        incoming.add(new Inbound(source, receivedEpoch, apply));
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
            ArchaeologyJournalLogLocalStore.flush();
            JournalUiPreferencesStore.flushIfDirty();
            return;
        }
        if (session == null || session.source != source) {
            session = new Session(source, ++epoch);
            ArchaeologyJournalLogLocalStore.beginConnection(session.mirror);
            JournalUiPreferencesStore.beginConnection();
            resetConnectionStates();
            current = session;
        }
        if (!session.loaded) {
            Path logPath = JournalLocalPersistence.resolveCurrentPath(ArchaeologyJournalLogLocalStore.STORAGE_FILE);
            Path preferencesPath = JournalLocalPersistence.resolveCurrentPath(JournalUiPreferencesStore.STORAGE_FILE);
            if (logPath == null || preferencesPath == null) {
                return;
            }
            ArchaeologyJournalLogLocalStore.loadCurrentPath(logPath);
            JournalUiPreferencesStore.loadCurrentPath(preferencesPath);
            session.loaded = true;
        }
        Inbound message;
        while ((message = incoming.poll()) != null) {
            if (message.source() == session.source
                    && (message.epoch() == 0L || message.epoch() == session.epoch)) {
                message.apply().run();
            }
        }
        ScenarioSimulationClientState.tick();
        ArchaeologyJournalLogLocalStore.tick();
        JournalUiPreferencesStore.tick();
    }

    public static void disconnect() {
        ArchaeologyJournalLogLocalStore.flush();
        JournalUiPreferencesStore.beginConnection();
        current = null;
        epoch++;
        incoming.clear();
        ArchaeologyJournalLogLocalStore.beginConnection(
                new JournalMirror(System::currentTimeMillis, () -> {}));
        resetConnectionStates();
    }

    private static void resetConnectionStates() {
        ArchaeologyJournalClientState.resetConnectionMemory();
        ScenarioSimulationClientState.clear();
        for (Runnable reset : connectionResets) {
            reset.run();
        }
    }
}
