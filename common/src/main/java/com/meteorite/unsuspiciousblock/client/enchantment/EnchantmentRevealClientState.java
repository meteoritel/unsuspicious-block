package com.meteorite.unsuspiciousblock.client.enchantment;

import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;

import java.util.List;
import java.util.Map;

/**
 * 附魔揭示客户端缓存状态。
 * 接收服务端下发的完整候选列表，按 {@code containerId} 缓存，
 * 供 {@code EnchantmentScreenMixin} 渲染 tooltip 时查询。
 * <p>
 * 列表完全由服务端权威计算，客户端仅做展示，不自行复刻 vanilla 算法。
 */
public final class EnchantmentRevealClientState {

    private EnchantmentRevealClientState() {}

    // 当前缓存的揭示数据：containerId → (slot → 候选条目列表)
    private static volatile CachedReveal current;

    /** 接收服务端下发的同步包 */
    public static void receive(SyncEnchantmentRevealListPayload payload) {
        if (payload == null) {
            return;
        }
        if (!payload.reveal()) {
            // 条件不满足：清空同 containerId 的缓存
            if (current != null && current.containerId == payload.containerId()) {
                current = null;
            }
            return;
        }
        // 按 slot 分组
        Map<Integer, List<SyncEnchantmentRevealListPayload.Entry>> bySlot = new java.util.HashMap<>();
        for (SyncEnchantmentRevealListPayload.Entry e : payload.entries()) {
            bySlot.computeIfAbsent(e.slot(), k -> new java.util.ArrayList<>()).add(e);
        }
        current = new CachedReveal(payload.containerId(), Map.copyOf(bySlot));
    }

    /** 查询指定菜单 + 槽位的候选列表；无缓存时返回 null（调用方回退 vanilla tooltip） */
    public static List<SyncEnchantmentRevealListPayload.Entry> getList(int containerId, int slot) {
        CachedReveal c = current;
        if (c == null || c.containerId != containerId) {
            return null;
        }
        return c.bySlot.get(slot);
    }

    /** 玩家断线时清空，避免跨会话残留 */
    public static void reset() {
        current = null;
    }

    private record CachedReveal(int containerId, Map<Integer, List<SyncEnchantmentRevealListPayload.Entry>> bySlot) {}
}
