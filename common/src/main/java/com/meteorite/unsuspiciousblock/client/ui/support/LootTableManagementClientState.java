package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Preview;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Result;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestLootTableManagementPayload.View;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootManagementDataPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootManagementDataPayload.Kind;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.ConfigView;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Receipt;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前客户端连接内的公共索引、个人状态与请求回执。 */
public final class LootTableManagementClientState {
    private static ConnectionState connection = new ConnectionState();
    private static long revision;

    private LootTableManagementClientState() {}

    private static final class ConnectionState {
        private List<SyncLootTableManagementPayload.Entry> entries = List.of();
        private List<SyncLootTableManagementPayload.RecentEntry> recent = List.of();
        private Map<String, Map<String, String>> unresolvedNames = Map.of();
        private final Map<Integer, List<Result>> nameResults = new LinkedHashMap<>();
        private long indexRevision = -1;
        private boolean canEditNames;
        private @Nullable ConfigView configuration;
        private final Map<Integer, Receipt> receipts = new LinkedHashMap<>();
        private @Nullable Preview preview;
        private long previewRequest;
        private int nextRequestId;
        private final Map<Kind, Assembly> incoming = new EnumMap<>(Kind.class);
    }

    private static final class Assembly {
        private final long revision;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int nextSequence;

        private Assembly(long revision) {
            this.revision = revision;
        }
    }

    public static void receive(SyncLootTableManagementPayload payload) {
        connection.configuration = payload.config();
        connection.canEditNames = payload.canEditNames();
        if (payload.recentEntries() != null) {
            connection.recent = List.copyOf(payload.recentEntries());
            revision++;
        }
        if (payload.receipt() != null) {
            Receipt receipt = payload.receipt();
            connection.receipts.put(receipt.requestId(), receipt);
            if (connection.receipts.size() > 16) {
                connection.receipts.remove(connection.receipts.keySet().iterator().next());
            }
        }
    }

    /** 分片完整到达后才替换客户端读取的快照。 */
    public static void receiveData(SyncLootManagementDataPayload payload) {
        if (payload.sequence() == 0) connection.incoming.put(payload.kind(), new Assembly(payload.revision()));
        Assembly assembly = connection.incoming.get(payload.kind());
        if (assembly == null || assembly.revision != payload.revision()
                || assembly.nextSequence != payload.sequence()) return;
        assembly.bytes.writeBytes(payload.bytes());
        assembly.nextSequence++;
        if (!payload.last()) return;
        connection.incoming.remove(payload.kind());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(assembly.bytes.toByteArray()));
        try {
            switch (payload.kind()) {
                case INDEX -> {
                    connection.entries = List.copyOf(SyncLootTableManagementPayload.readEntries(buffer));
                    connection.indexRevision = payload.revision();
                    revision++;
                }
                case NAMES -> {
                    ClientLootTableLanguageStore.receiveServerTranslations(readNames(buffer));
                    connection.unresolvedNames = readNames(buffer);
                    revision++;
                }
                case NAME_RESULT -> connection.nameResults.put((int) payload.revision(),
                        SyncLootManagementDataPayload.readNameResults(buffer));
                case PREVIEW -> {
                    connection.preview = SyncLootTableManagementPayload.readPreview(buffer);
                    connection.previewRequest = payload.revision();
                }
            }
        } finally {
            buffer.release();
        }
    }

    private static Map<String, Map<String, String>> readNames(FriendlyByteBuf buf) {
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        int languages = buf.readVarInt();
        for (int index = 0; index < languages; index++) {
            String language = buf.readUtf();
            int count = buf.readVarInt();
            Map<String, String> names = new LinkedHashMap<>();
            for (int entry = 0; entry < count; entry++) names.put(buf.readUtf(), buf.readUtf());
            translations.put(language, Map.copyOf(names));
        }
        return Map.copyOf(translations);
    }

    public static void reset() {
        connection = new ConnectionState();
        revision++;
    }

    public static List<SyncLootTableManagementPayload.Entry> entries() { return connection.entries; }
    public static List<SyncLootTableManagementPayload.RecentEntry> recentEntries() { return connection.recent; }
    public static Map<String, Map<String, String>> unresolvedNames() { return connection.unresolvedNames; }
    public static boolean canEdit() { return connection.canEditNames; }
    public static boolean indexReady() { return connection.indexRevision >= 0; }
    public static @Nullable ConfigView configuration() { return connection.configuration; }
    public static long revision() { return revision; }

    public static @Nullable Receipt receipt(int requestId) {
        return connection.receipts.remove(requestId);
    }

    public static @Nullable Preview preview(int requestId) {
        return connection.previewRequest == requestId ? connection.preview : null;
    }

    public static int submitNames(List<LootTableNameEdit> entries) {
        int requestId = ++connection.nextRequestId;
        UpdateLootTableTranslationsPayload.split(requestId, entries).forEach(ClientNetwork::sendToServer);
        return requestId;
    }

    public static @Nullable List<Result> takeNameResults(int requestId) {
        return connection.nameResults.remove(requestId);
    }

    public static void requestSnapshot() { request(View.INDEX); }
    public static void requestConfiguration() { request(View.CONFIG); }
    public static void closeSnapshot() { request(View.CLOSED); }

    private static void request(View view) {
        ClientNetwork.sendToServer(new RequestLootTableManagementPayload(view, connection.indexRevision));
    }

    /** 请求规则差异，后续提交使用同一配置与资源版本。 */
    public static int requestPreview(long revision, long resourceRevision, UpdateTrackedLootTablePayload.Edit edit) {
        return send(revision, resourceRevision, true, edit);
    }

    public static int submit(long revision, long resourceRevision, UpdateTrackedLootTablePayload.Edit edit) {
        return send(revision, resourceRevision, false, edit);
    }

    private static int send(long revision, long resourceRevision, boolean preview, UpdateTrackedLootTablePayload.Edit edit) {
        int requestId = ++connection.nextRequestId;
        UpdateTrackedLootTablePayload.split(requestId, revision, resourceRevision, preview, edit)
                .forEach(ClientNetwork::sendToServer);
        return requestId;
    }
}
