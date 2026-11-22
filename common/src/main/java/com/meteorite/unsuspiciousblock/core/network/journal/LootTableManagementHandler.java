package com.meteorite.unsuspiciousblock.core.network.journal;

import com.google.gson.JsonParseException;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.tracking.RecentLootTableService;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingSettings;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Application;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Snapshot;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Result;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Status;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableTranslationStore;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestLootTableManagementPayload.View;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootManagementDataPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootManagementDataPayload.Kind;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.ConfigView;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Entry;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Receipt;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.RecentEntry;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** 管理页随连接订阅状态，公共索引按资源、目录和规则复用。 */
public final class LootTableManagementHandler {
    private static final int EDIT_PERMISSION_LEVEL = 2;
    private static final Map<ServerPlayer, Subscription> SUBSCRIPTIONS = new WeakHashMap<>();
    private static final Map<ServerPlayer, ConfigUpload> CONFIG_UPLOADS = new WeakHashMap<>();
    private static final Map<ServerPlayer, NameUpload> NAME_UPLOADS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Receipt> PENDING = new WeakHashMap<>();
    private static @Nullable RegisteredTables registeredTables;
    private static @Nullable ManagementIndex managementIndex;
    private static long indexRevision;
    private static long nameRevision;
    private static List<SyncLootManagementDataPayload> namePackets = List.of();

    private LootTableManagementHandler() {}

    /** 打开页面时获取状态与缺少的公共数据，关闭页面时取消订阅。 */
    public static void handleRequest(ServerPlayer player, RequestLootTableManagementPayload payload) {
        if (payload.view() == View.CLOSED) {
            SUBSCRIPTIONS.remove(player);
            return;
        }
        SUBSCRIPTIONS.put(player, new Subscription(payload.view(), payload.indexRevision()));
        sync(player, payload.view() == View.INDEX, null);
    }

    /** 保存前校验草稿的配置与数据包版本，预览经正式目录算法生成。 */
    public static void handleUpdate(ServerPlayer player, UpdateTrackedLootTablePayload payload) {
        MinecraftServer server = player.server;
        EditResult result = receiveEdit(server, player, payload);
        if (result == null) return;
        long revision = ServerLootTableConfigManager.snapshot().revision();
        boolean pending = result == EditResult.SAVED && ServerLootTableConfigManager.application() == Application.PENDING;
        Receipt receipt = new Receipt(payload.requestId(), result == EditResult.SAVED && !pending
                ? EditResult.APPLIED : result, revision);
        if (result == EditResult.SAVED) {
            Receipt earlier = PENDING.remove(player);
            if (earlier != null) sendStatus(player, null, new Receipt(
                    earlier.requestId(), EditResult.SUPERSEDED, earlier.revision()));
            if (pending) {
                PENDING.put(player, receipt);
            }
            if (pending) broadcastOthers(server, player);
            else broadcastApplied(server, player);
        }
        sync(player, false, receipt);
    }

    private static @Nullable EditResult receiveEdit(MinecraftServer server, ServerPlayer player,
                                                     UpdateTrackedLootTablePayload payload) {
        Snapshot config = ServerLootTableConfigManager.snapshot();
        if (!canEdit(server, player) || !config.canSave()) {
            CONFIG_UPLOADS.remove(player);
            return EditResult.READ_ONLY;
        }
        if (!ServerLootTableConfigManager.matchesDraft(server, payload.revision(), payload.resourceRevision())) {
            CONFIG_UPLOADS.remove(player);
            return EditResult.CONFLICT;
        }
        UpdateTrackedLootTablePayload.Edit edit = payload.edit();
        if (edit instanceof UpdateTrackedLootTablePayload.Replace part) {
            if (payload.sequence() == 0) CONFIG_UPLOADS.put(player, new ConfigUpload(payload));
            ConfigUpload upload = CONFIG_UPLOADS.get(player);
            // 剩余切片属于服务端已拒绝的旧请求时，连接仍继续处理新草稿。
            if (upload == null || upload.first.requestId() != payload.requestId()) return null;
            if (!upload.accept(payload, part.settingsJson())) {
                CONFIG_UPLOADS.remove(player);
                return EditResult.INVALID;
            }
            if (!payload.last()) return null;
            CONFIG_UPLOADS.remove(player);
            edit = new UpdateTrackedLootTablePayload.Replace(upload.text.toString());
        }
        return editTracking(server, player, payload, edit, config);
    }

    private static EditResult editTracking(MinecraftServer server, ServerPlayer player,
            UpdateTrackedLootTablePayload payload, UpdateTrackedLootTablePayload.Edit edit, Snapshot config) {
        LootTrackingSettings settings;
        ResourceLocation selected = null;
        try {
            switch (edit) {
                case UpdateTrackedLootTablePayload.Single single -> {
                    if (!isManageable(server, single.tableId())) return EditResult.INVALID;
                    selected = single.tableId();
                    settings = config.rules().edit(selected, single.action());
                }
                case UpdateTrackedLootTablePayload.Replace replace -> {
                    settings = LootTrackingConfigFile.decodeSettings(replace.settingsJson());
                }
            }
        } catch (JsonParseException | IllegalArgumentException exception) {
            return EditResult.INVALID;
        }
        if (!payload.preview()) {
            return ServerLootTableConfigManager.save(server, payload.revision(), payload.resourceRevision(), settings);
        }
        try {
            var preview = ServerLootTableConfigManager.preview(server, settings, selected);
            send(player, SyncLootManagementDataPayload.encode(Kind.PREVIEW, payload.requestId(),
                    buf -> SyncLootTableManagementPayload.writePreview(buf, preview)));
            return EditResult.PREVIEWED;
        } catch (RuntimeException exception) {
            Constants.LOG.warn("{}战利品表规则预览失败", Constants.LOG_TAG, exception);
            return EditResult.PREVIEW_FAILED;
        }
    }

    private static boolean canEdit(MinecraftServer server, ServerPlayer player) {
        return player.hasPermissions(EDIT_PERMISSION_LEVEL) || !server.isDedicatedServer()
                && !server.isPublished() && server.isSingleplayerOwner(player.getGameProfile());
    }

    /** 保存补充名称后，只发送名称数据。 */
    public static void handleTranslationUpdate(ServerPlayer player, UpdateLootTableTranslationsPayload payload) {
        if (payload.sequence() == 0) NAME_UPLOADS.put(player, new NameUpload(payload.requestId()));
        NameUpload upload = NAME_UPLOADS.get(player);
        if (upload == null || upload.requestId != payload.requestId() || upload.nextSequence != payload.sequence()) return;
        if (upload.edits.size() + payload.entries().size() > UpdateLootTableTranslationsPayload.MAX_ENTRIES) {
            NAME_UPLOADS.remove(player);
            send(player, SyncLootManagementDataPayload.encode(Kind.NAME_RESULT, payload.requestId(),
                    buf -> SyncLootManagementDataPayload.writeNameResults(buf, List.of())));
            return;
        }
        upload.edits.addAll(payload.entries());
        upload.nextSequence++;
        if (!payload.last()) return;
        NAME_UPLOADS.remove(player);
        saveNames(player, payload.requestId(), upload.edits);
    }

    private static void saveNames(ServerPlayer player, int requestId, List<LootTableNameEdit> edits) {
        MinecraftServer server = player.server;
        boolean editable = canEdit(server, player);
        List<Result> results = new ArrayList<>();
        List<LootTableNameEdit> accepted = new ArrayList<>();
        Set<NameKey> keys = new HashSet<>();
        for (LootTableNameEdit edit : edits) {
            if (!editable) {
                results.add(new Result(edit.tableId(), edit.language(), Status.READ_ONLY, ""));
                continue;
            }
            String value = edit.value().trim();
            if (!isManageable(server, edit.tableId()) || !LootTableNameEdit.isLanguage(edit.language())
                    || !LootTableNameEdit.isName(value)
                    || !edit.legacyKey().isEmpty() && !LootTableNames.isGeneratedTranslationKey(edit.legacyKey())
                    || !keys.add(new NameKey(edit.tableId(), edit.language()))) {
                results.add(new Result(edit.tableId(), edit.language(), Status.INVALID, ""));
                continue;
            }
            accepted.add(new LootTableNameEdit(edit.tableId(), edit.language(), value, edit.expected(), edit.legacyKey()));
        }
        results.addAll(LootTableTranslationStore.save(accepted));
        if (results.stream().anyMatch(result -> result.status() == Status.SAVED)) refreshNames(server);
        send(player, SyncLootManagementDataPayload.encode(Kind.NAME_RESULT, requestId,
                buf -> SyncLootManagementDataPayload.writeNameResults(buf, results)));
    }

    private record NameKey(ResourceLocation id, String language) {}

    /** 玩家登录仅需要补充名称，管理索引由打开页面触发。 */
    public static void syncNames(ServerPlayer player) {
        if (namePackets.isEmpty()) encodeNames();
        send(player, namePackets);
    }

    /** 数据包重载或成功写入实例名称后发布补充名称。 */
    public static void refreshNames(MinecraftServer server) {
        encodeNames();
        server.getPlayerList().getPlayers().forEach(LootTableManagementHandler::syncNames);
    }

    private static void encodeNames() {
        Map<String, Map<String, String>> names = LootTableTranslationStore.snapshot();
        namePackets = SyncLootManagementDataPayload.encode(Kind.NAMES, ++nameRevision, buf -> {
            writeNames(buf, names);
            writeNames(buf, LootTableTranslationStore.unresolvedLegacyNames());
        });
    }

    private static void writeNames(FriendlyByteBuf buf, Map<String, Map<String, String>> names) {
        buf.writeVarInt(names.size());
        names.forEach((language, values) -> {
            buf.writeUtf(language);
            buf.writeVarInt(values.size());
            values.forEach((key, value) -> {
                buf.writeUtf(key);
                buf.writeUtf(value);
            });
        });
    }

    /** 应用完成后通知订阅中的页面及等待该次目录发布的请求。 */
    public static void broadcast(MinecraftServer server) {
        broadcastApplied(server, null);
    }

    private static void broadcastApplied(MinecraftServer server, @Nullable ServerPlayer sender) {
        Snapshot current = ServerLootTableConfigManager.snapshot();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == sender) continue;
            Receipt pending = PENDING.remove(player);
            Receipt terminal = null;
            if (pending != null) {
                EditResult result = pending.revision() != current.revision() ? EditResult.SUPERSEDED
                        : ServerLootTableConfigManager.application() == Application.FAILED
                        ? EditResult.APPLY_FAILED : EditResult.APPLIED;
                terminal = new Receipt(pending.requestId(), result, pending.revision());
            }
            if (SUBSCRIPTIONS.containsKey(player) || terminal != null) sync(player, false, terminal);
        }
    }

    private static void broadcastOthers(MinecraftServer server, ServerPlayer sender) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != sender && SUBSCRIPTIONS.containsKey(player)) sync(player, false, null);
        }
    }

    /** 释放世界的订阅与已编码快照。 */
    public static void stop() {
        SUBSCRIPTIONS.clear();
        PENDING.clear();
        NAME_UPLOADS.clear();
        CONFIG_UPLOADS.clear();
        registeredTables = null;
        managementIndex = null;
        namePackets = List.of();
    }

    private static void sync(ServerPlayer player, boolean includeRecent, @Nullable Receipt receipt) {
        Subscription subscription = SUBSCRIPTIONS.get(player);
        if (subscription != null && subscription.view() == View.INDEX) {
            ManagementIndex index = index(player.server);
            if (index.revision() != subscription.indexRevision()) {
                send(player, index.packets());
                SUBSCRIPTIONS.put(player, new Subscription(View.INDEX, index.revision()));
            }
        }
        sendStatus(player, includeRecent ? recent(player) : null, receipt);
    }

    private static void sendStatus(ServerPlayer player, @Nullable List<RecentEntry> recent, @Nullable Receipt receipt) {
        Snapshot config = ServerLootTableConfigManager.snapshot();
        String settings = LootTrackingConfigFile.encodeSettings(config.settings());
        boolean canEdit = canEdit(player.server, player);
        ConfigView view = new ConfigView(config.revision(), ServerLootTableConfigManager.resourceRevision(),
                ServerLootTableConfigManager.appliedRevision(), ArchaeologyJournalServerCatalog.currentGenerationId(),
                canEdit, config.canSave(), ServerLootTableConfigManager.application(),
                settings.length() <= UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS ? settings : null);
        Services.NETWORK.sendToPlayer(player, new SyncLootTableManagementPayload(canEdit, view, recent, receipt));
    }

    private static List<RecentEntry> recent(ServerPlayer player) {
        var state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) return List.of();
        Set<ResourceLocation> registered = registered(player.server).ids();
        return state.getRecentLootTables().stream().filter(entry -> registered.contains(entry.tableId()))
                .map(entry -> new RecentEntry(entry.tableId(), entry.encounterOrder())).toList();
    }

    private static boolean isManageable(MinecraftServer server, ResourceLocation tableId) {
        return registered(server).ids().contains(tableId);
    }

    private static RegisteredTables registered(MinecraftServer server) {
        var resources = server.reloadableRegistries();
        if (registeredTables == null || registeredTables.resources() != resources) {
            List<ResourceLocation> sorted = resources.getKeys(Registries.LOOT_TABLE).stream()
                    .filter(RecentLootTableService::isSupported).sorted().toList();
            registeredTables = new RegisteredTables(resources, sorted, Set.copyOf(sorted));
        }
        return registeredTables;
    }

    private static ManagementIndex index(MinecraftServer server) {
        Snapshot config = ServerLootTableConfigManager.snapshot();
        LootTrackingSettings settings = config.settings();
        IndexVersion version = new IndexVersion(ServerLootTableConfigManager.resourceRevision(),
                ArchaeologyJournalServerCatalog.currentGenerationId(), settings.followBuiltinRules(),
                settings.disabledBuiltinRules(), settings.additionalTrackingRules(), settings.excludedLootTables());
        if (managementIndex == null || !managementIndex.version().equals(version)) {
            Set<ResourceLocation> roots = ArchaeologyJournalServerCatalog.rootTableIds();
            Set<ResourceLocation> collected = ArchaeologyJournalServerCatalog.staticTables().keySet();
            List<Entry> entries = registered(server).sorted().stream()
                    .map(id -> {
                        var rules = config.rules().explain(id);
                        return new Entry(id, rules.match(), roots.contains(id), collected.contains(id),
                                rules.exactOverride(), ArchaeologyJournalServerCatalog.parents(id), rules.selectors());
                    }).toList();
            long revision = ++indexRevision;
            managementIndex = new ManagementIndex(version, revision,
                    SyncLootManagementDataPayload.encode(Kind.INDEX, revision,
                            buf -> SyncLootTableManagementPayload.writeEntries(buf, entries)));
        }
        return managementIndex;
    }

    private static void send(ServerPlayer player, List<SyncLootManagementDataPayload> packets) {
        packets.forEach(packet -> Services.NETWORK.sendToPlayer(player, packet));
    }

    private static final class ConfigUpload {
        private final UpdateTrackedLootTablePayload first;
        private final StringBuilder text = new StringBuilder();
        private int nextSequence;

        private ConfigUpload(UpdateTrackedLootTablePayload first) { this.first = first; }

        private boolean accept(UpdateTrackedLootTablePayload part, String content) {
            if (part.sequence() != nextSequence || part.preview() != first.preview()
                    || part.revision() != first.revision() || part.resourceRevision() != first.resourceRevision()
                    || text.length() + content.length() > UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS) return false;
            text.append(content);
            nextSequence++;
            return true;
        }
    }

    private static final class NameUpload {
        private final int requestId;
        private final List<LootTableNameEdit> edits = new ArrayList<>();
        private int nextSequence;

        private NameUpload(int requestId) { this.requestId = requestId; }
    }

    private record Subscription(View view, long indexRevision) {}
    private record RegisteredTables(ReloadableServerRegistries.Holder resources, List<ResourceLocation> sorted,
                                    Set<ResourceLocation> ids) {}
    private record IndexVersion(long resources, long catalog, boolean followBuiltins, List<String> disabled,
                                List<String> additional, List<String> excluded) {}
    private record ManagementIndex(IndexVersion version, long revision, List<SyncLootManagementDataPayload> packets) {}
}
