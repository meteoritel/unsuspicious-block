package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 客户端扫描仪 HUD 状态：维护最多五条独立生命周期的信息条目。
 *  生命周期与透明度基于毫秒时间戳计算，避免 20Hz tick 粒度与线程可见性导致的透明度跳变。
 *  透明度曲线全程连续：淡入 200ms → 保持 → 最后 3 秒淡出；
 *  刷新（同坐标再次扫描）时从当前透明度平滑回升，不产生不透明度硬跳变。
 *  面板透明度始终锚定最新一条条目（列表首位）的淡出曲线（fadeOutAlpha，不含淡入），
 *  保证面板淡出与最新信息同步，且新条目到达时面板保持可见。
 *  注意：文字 alpha 字节低于 4 时原版 Font.adjustColor 会强制为不透明，
 *  渲染端（SuspiciousReaderHud.MIN_TEXT_ALPHA）必须跳过该区间。
 */
public final class ReaderScanHudState {
    private static final long DISPLAY_MS = 8000L;
    private static final long FADE_OUT_MS = 3000L;
    private static final long FADE_IN_MS = 200L;
    private static final int MAX_ENTRIES = 5;

    private static final ReaderScanHudState.Snapshot EMPTY_SNAPSHOT = new ReaderScanHudState.Snapshot(List.of(), 0);

    private static final Object LOCK = new Object();
    private static final List<HudEntry> entries = new ArrayList<>();
    private static int lootContainerCount;
    private static volatile boolean hudEnabled = true;

    private ReaderScanHudState() {
    }

    /** 渲染线程单帧快照：条目、面板锚点与容器计数在同一次加锁中读取，保证面板与条目同帧一致。 */
    public record Snapshot(List<HudEntry> entries, int lootContainerCount) {
        // 面板锚点即最新条目（列表首位），面板淡出与其绑定
        public HudEntry anchor() {
            return entries.isEmpty() ? null : entries.get(0);
        }
    }

    // 接收新扫描结果并插入现有 HUD，单条信息拥有独立的 8 秒生命周期
    public static void receive(SyncReaderScanResultPayload payload) {
        List<SyncReaderScanResultPayload.ScanEntry> sorted = new ArrayList<>(payload.results());
        LocalPlayer player = Minecraft.getInstance().player;
        Comparator<SyncReaderScanResultPayload.ScanEntry> comparator =
                Comparator.comparing(SyncReaderScanResultPayload.ScanEntry::alreadyScanned)
                        .thenComparing(SyncReaderScanResultPayload.ScanEntry::isEmpty);
        if (player != null) {
            comparator = comparator.thenComparingDouble(
                    entry -> entry.pos().distSqr(player.blockPosition()));
        }
        sorted.sort(comparator);

        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            // 从后往前处理，使本轮排序优先级在 HUD 中保持不变，同时让新条目位于队列顶部。
            for (int i = sorted.size() - 1; i >= 0; i--) {
                SyncReaderScanResultPayload.ScanEntry scanEntry = sorted.get(i);
                HudEntry existing = findByPositionLocked(scanEntry.pos());
                if (existing != null) {
                    existing.refresh(scanEntry, now);
                    entries.remove(existing);
                    entries.addFirst(existing);
                } else {
                    entries.addFirst(new HudEntry(scanEntry, now));
                }
            }
            if (sorted.isEmpty() && !payload.lootContainers().isEmpty()) {
                HudEntry existing = findContainerNoticeLocked();
                if (existing != null) {
                    existing.refresh(null, now);
                    entries.remove(existing);
                    entries.addFirst(existing);
                } else {
                    entries.addFirst(new HudEntry(null, now));
                }
            }
            while (entries.size() > MAX_ENTRIES) {
                entries.removeLast();
            }
            lootContainerCount = payload.lootContainers().size();
        }

        if (payload.highlightResults()) {
            ReaderScanHighlightState.receive(payload.suspiciousBlocks(), payload.lootContainers());
        }
    }

    private static HudEntry findByPositionLocked(net.minecraft.core.BlockPos position) {
        for (HudEntry entry : entries) {
            if (entry.scanEntry != null && entry.scanEntry.pos().equals(position)) {
                return entry;
            }
        }
        return null;
    }

    private static HudEntry findContainerNoticeLocked() {
        for (HudEntry entry : entries) {
            if (entry.scanEntry == null) {
                return entry;
            }
        }
        return null;
    }

    // 客户端每 tick 更新按键状态与清理过期条目，不做任何整体清空以保证淡出曲线完整
    public static void tick() {
        while (ModKeyBindings.READER_HUD_TOGGLE.consumeClick()) {
            hudEnabled = !hudEnabled;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            boolean hadScanEntries = entries.stream().anyMatch(HudEntry::isScanEntry);
            entries.removeIf(entry -> entry.isExpired(now));
            // 容器提示不能长期脱离真实扫描信息独立存在；最后一条真实信息消失时，
            // 把容器提示的剩余寿命压缩为一次完整的淡出，平滑关闭整个 HUD。
            if (hadScanEntries && entries.stream().noneMatch(HudEntry::isScanEntry)) {
                for (HudEntry entry : entries) {
                    entry.capToFadeOut(now);
                }
            }
        }
    }

    // 供渲染线程调用：HUD 是否被按键关闭
    public static boolean isHudEnabled() {
        return hudEnabled;
    }

    // 供渲染线程调用：单帧一致快照（hudEnabled 关闭时返回空快照）
    public static Snapshot snapshot() {
        if (!hudEnabled) {
            return EMPTY_SNAPSHOT;
        }
        synchronized (LOCK) {
            return new Snapshot(List.copyOf(entries), lootContainerCount);
        }
    }

    // 断线时清除上一个世界的结果
    public static void reset() {
        synchronized (LOCK) {
            entries.clear();
            lootContainerCount = 0;
            hudEnabled = true;
        }
    }

    /** 单条 HUD 信息及其生命周期。
     *  使用绝对毫秒时间戳（淡入起点与过期时间），避免 tick 粒度与线程可见性导致的透明度跳变。
     */
    public static final class HudEntry {
        private SyncReaderScanResultPayload.ScanEntry scanEntry;
        private long bornMs;
        private long expiryMs;

        private HudEntry(SyncReaderScanResultPayload.ScanEntry scanEntry, long now) {
            this.scanEntry = scanEntry;
            this.bornMs = now;
            this.expiryMs = now + DISPLAY_MS;
        }

        // 刷新条目内容与生命周期，用于同一坐标再次扫描时的去重
        private void refresh(SyncReaderScanResultPayload.ScanEntry scanEntry, long now) {
            this.scanEntry = scanEntry;
            // 将淡入起点回溯到当前透明度对应的位置，使刷新前后透明度曲线连续、平滑回升
            this.bornMs = now - (long) (alpha(now) * FADE_IN_MS);
            this.expiryMs = now + DISPLAY_MS;
        }

        // 把剩余寿命压缩为一次完整淡出（用于容器提示随最后一条真实信息平滑关闭）
        private void capToFadeOut(long now) {
            this.expiryMs = Math.min(this.expiryMs, now + FADE_OUT_MS);
        }

        public SyncReaderScanResultPayload.ScanEntry scanEntry() {
            return scanEntry;
        }

        public boolean isScanEntry() {
            return scanEntry != null;
        }

        // 仅淡出曲线（不含淡入），供面板锚点使用：面板本身已可见，不随新条目淡入而闪烁
        public float fadeOutAlpha(long now) {
            long remaining = expiryMs - now;
            if (remaining >= FADE_OUT_MS) {
                return 1.0F;
            }
            return remaining <= 0L ? 0.0F : (float) remaining / (float) FADE_OUT_MS;
        }

        // 当前透明度：淡入 × 保持 × 淡出，基于系统时间，渲染线程每帧独立计算
        public float alpha(long now) {
            float fadeIn = Math.min(1.0F, (float) (now - bornMs) / (float) FADE_IN_MS);
            return fadeIn * fadeOutAlpha(now);
        }

        public boolean isExpired(long now) {
            return now >= expiryMs;
        }
    }
}
