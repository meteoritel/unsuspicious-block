package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;

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

    private LootTableManagementClientState() {
    }

    public static void receive(SyncLootTableManagementPayload payload) {
        entries = List.copyOf(payload.entries());
        recentEntries = List.copyOf(payload.recentEntries());
        translations = copyTranslations(payload.translations());
        canEdit = payload.canEdit();
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

    public static long revision() {
        return revision.get();
    }

    private static Map<String, Map<String, String>> copyTranslations(
            Map<String, Map<String, String>> source) {
        java.util.LinkedHashMap<String, Map<String, String>> copy = new java.util.LinkedHashMap<>();
        source.forEach((language, values) -> copy.put(language, Map.copyOf(values)));
        return Map.copyOf(copy);
    }
}
