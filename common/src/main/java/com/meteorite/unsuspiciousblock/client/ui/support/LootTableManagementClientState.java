package com.meteorite.unsuspiciousblock.client.ui.support;

import java.util.LinkedHashMap;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.ConfigView;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Receipt;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端战利品表管理快照——供独立管理页面与当前连接的内存语言补充层读取。
 */
public final class LootTableManagementClientState {
    private static final AtomicLong revision = new AtomicLong();
    private static volatile List<SyncLootTableManagementPayload.Entry> entries = List.of();
    private static volatile List<SyncLootTableManagementPayload.RecentEntry> recentEntries = List.of();
    private static volatile Map<String, Map<String, String>> translations = Map.of();
    private static volatile boolean canEdit;
    private static volatile @Nullable ConfigView configuration;
    private static volatile @Nullable Receipt lastReceipt;
    private static int nextRequestId;

    private LootTableManagementClientState() {
    }

    public static void receive(SyncLootTableManagementPayload payload) {
        entries = List.copyOf(payload.entries());
        recentEntries = List.copyOf(payload.recentEntries());
        translations = copyTranslations(payload.translations());
        canEdit = payload.canEditNames();
        configuration = payload.config();
        if (payload.receipt() != null) lastReceipt = payload.receipt();
        revision.incrementAndGet();
        ClientLootTableLanguageStore.receiveServerTranslations(translations);
    }

    // 断开连接时清空连接级快照：切服后首个管理页响应到来前不应展示上一台服务器的条目或编辑权限。
    // 生成 key 的资源翻译由 ClientLootTableLanguageStore.resetOnDisconnect() 单独清理，两端入口都已调用。
    public static void reset() {
        entries = List.of();
        recentEntries = List.of();
        translations = Map.of();
        canEdit = false;
        configuration = null;
        lastReceipt = null;
        revision.incrementAndGet();
    }

    public static List<SyncLootTableManagementPayload.Entry> entries() {
        return entries;
    }

    public static List<SyncLootTableManagementPayload.RecentEntry> recentEntries() {
        return recentEntries;
    }

    public static Map<String, Map<String, String>> translations() {
        return translations;
    }

    public static boolean canEdit() {
        return canEdit;
    }

    public static @Nullable ConfigView configuration() {
        return configuration;
    }

    public static @Nullable Receipt receipt(int requestId) {
        Receipt receipt = lastReceipt;
        return receipt != null && receipt.requestId() == requestId ? receipt : null;
    }

    /** 请求当前连接的服务端规则和管理列表。 */
    public static void requestSnapshot() {
        ClientNetwork.sendToServer(new RequestLootTableManagementPayload());
    }

    /** 提交基于指定配置版本的编辑，并返回对应回执编号。 */
    public static int submit(long revision, UpdateTrackedLootTablePayload.Edit edit) {
        int requestId = ++nextRequestId;
        ClientNetwork.sendToServer(new UpdateTrackedLootTablePayload(requestId, revision, edit));
        return requestId;
    }

    public static long revision() {
        return revision.get();
    }

    private static Map<String, Map<String, String>> copyTranslations(
            Map<String, Map<String, String>> source) {
        LinkedHashMap<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.forEach((language, values) -> copy.put(language, Map.copyOf(values)));
        return Map.copyOf(copy);
    }
}
