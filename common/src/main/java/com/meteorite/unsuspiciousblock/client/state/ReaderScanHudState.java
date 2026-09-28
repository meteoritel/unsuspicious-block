package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload.ScanEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 整批扫描结果、地下目标选择和世界描边共用的客户端状态。 */
public final class ReaderScanHudState {
    private static final long DISPLAY_MS = 10_000L;
    private static final long MAX_DISPLAY_MS = 30_000L;
    private static final long FADE_MS = 500L;
    private static final Snapshot EMPTY =
            new Snapshot(List.of(), List.of(), List.of(), 0, false, 0, 0, new HudLayout());
    private static volatile Snapshot current = EMPTY;
    private static volatile Target focused;
    // HUD 开关只作用于本次客户端会话，刻意不写入任何持久化文件：
    // 断开连接后由 {@link #reset()} 恢复为显示，避免「上次关掉后忘了打开」的困惑状态。
    // 仅客户端 Render 线程读写（tick 与渲染同线程），无需 volatile。
    private static boolean hudEnabled = true;
    private static ClientLevel scanLevel;
    private static Target candidate;
    private static long candidateSince;

    private ReaderScanHudState() {
    }

    // 单调时钟避免系统时间调整造成动画跳变。
    public static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    // 网络回调在客户端线程执行；新一轮整体替换，展示行数不限制目标缓存。
    public static void receive(SyncReaderScanResultPayload payload) {
        long time = now();
        List<Target> targets = new ArrayList<>();
        for (ScanEntry entry : payload.results()) {
            ItemStack icon = entry.isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()));
            targets.add(new Target(entry.pos(), entry, icon));
        }
        for (BlockPos pos : payload.lootContainers()) targets.add(new Target(pos, null, ItemStack.EMPTY));
        scanLevel = Minecraft.getInstance().level;
        current = makeSnapshot(targets, payload.highlightResults(), time, time + DISPLAY_MS);
        focused = null;
        candidate = null;
    }

    // 只在结果变化时分组并缓存图标，不在每个渲染帧重复分配。
    private static Snapshot makeSnapshot(List<Target> targets, boolean range, long born, long expires) {
        Map<GroupKey, ItemGroup> groups = new LinkedHashMap<>();
        List<Target> blocks = new ArrayList<>();
        List<Target> containers = new ArrayList<>();
        int emptyCount = 0;
        for (Target target : targets) {
            ScanEntry entry = target.entry();
            if (entry == null) {
                containers.add(target);
                continue;
            }
            blocks.add(target);
            if (entry.itemId() == null) {
                emptyCount++;
                continue;
            }
            GroupKey key = new GroupKey(entry.itemId().toString(), entry.displayName(), entry.sealedByPlayer(), entry.crafterName());
            groups.compute(key, (k, old) -> new ItemGroup(target.icon(), entry.displayName(),
                    (old == null ? 0L : old.count()) + entry.count()));
        }
        return new Snapshot(List.copyOf(blocks), List.copyOf(containers), List.copyOf(groups.values()),
                emptyCount, range, born, expires, new HudLayout());
    }

    public static void tick() {
        while (ModKeyBindings.READER_HUD_TOGGLE.consumeClick()) hudEnabled = !hudEnabled;
        Minecraft mc = Minecraft.getInstance();
        if (scanLevel != mc.level) clearResults();
        long time = now();
        Snapshot snapshot = current;
        if (snapshot == EMPTY || time >= snapshot.expires()) {
            clearResults();
            return;
        }
        // 仅访问已加载区块，移除挖走或卸载的目标，不请求加载新区块。
        List<Target> valid = new ArrayList<>();
        for (Target target : snapshot.blocks()) {
            if (isValid(mc, target)) valid.add(target);
        }
        for (Target target : snapshot.containers()) {
            if (isValid(mc, target)) valid.add(target);
        }
        if (valid.size() != snapshot.blocks().size() + snapshot.containers().size()) {
            snapshot = makeSnapshot(valid, snapshot.range(), snapshot.born(), snapshot.expires());
            current = snapshot;
        }
        if (!hudEnabled || readerStack().isEmpty() || mc.screen != null || mc.options.hideGui) {
            focused = null;
            candidate = null;
            return;
        }
        Target next = findTarget(mc, valid);
        if (focused != null && !valid.contains(focused)) focused = null;
        if (next == null) {
            focused = null;
            candidate = null;
        } else if (next.equals(focused)) {
            candidate = null;
        } else if (!next.equals(candidate)) {
            candidate = next;
            candidateSince = time;
        } else if (time - candidateSince >= 100L) {
            focused = next;
        }
        if (focused != null) {
            // 阅读时延长整批显示，最多保留 30 秒；expires 恒不超过 born + MAX_DISPLAY_MS，clamp 安全。
            long expires = Math.clamp(time + 1500L, snapshot.expires(), snapshot.born() + MAX_DISPLAY_MS);
            // 只延长显示时间，数据与文本都没变，沿用同一派生量缓存
            current = new Snapshot(snapshot.blocks(), snapshot.containers(), snapshot.groups(), snapshot.emptyCount(),
                    snapshot.range(), snapshot.born(), expires, snapshot.hud());
        }
    }

    private static boolean isValid(Minecraft mc, Target target) {
        if (mc.level == null || !mc.level.hasChunk(SectionPos.blockToSectionCoord(target.pos().getX()),
                SectionPos.blockToSectionCoord(target.pos().getZ()))) return false;
        BlockEntity entity = mc.level.getBlockEntity(target.pos());
        return target.entry() == null ? entity != null : entity instanceof BrushableBlockEntityScanState;
    }

    // 仅对已扫描方块做射线检测，穿过表层沙子，沿视线取最近目标。
    private static Target findTarget(Minecraft mc, List<Target> targets) {
        if (mc.player == null) return null;
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        var look = camera.getLookVector();
        Vec3 end = origin.add(look.x() * 32.0, look.y() * 32.0, look.z() * 32.0);
        Target nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (Target target : targets) {
            AABB box = new AABB(target.pos()).inflate(0.08);
            var hit = box.clip(origin, end);
            if (!box.contains(origin) && hit.isEmpty()) continue;
            double distance = box.contains(origin) ? 0 : hit.orElseThrow().distanceToSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = target;
            }
        }
        return nearest;
    }

    public static ItemStack readerStack() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        if (player.getMainHandItem().is(ModItems.SUSPICIOUS_READER)) return player.getMainHandItem();
        if (player.getOffhandItem().is(ModItems.SUSPICIOUS_READER)) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    public static boolean isHudEnabled() {
        return hudEnabled;
    }

    public static Target focusedTarget() {
        return hudEnabled && !readerStack().isEmpty() ? focused : null;
    }

    public static Snapshot snapshot() {
        return scanLevel == Minecraft.getInstance().level ? current : EMPTY;
    }

    private static void clearResults() {
        current = EMPTY;
        focused = null;
        candidate = null;
        scanLevel = null;
    }

    // 断连/切世界时重置：结果清空，HUD 开关按设计恢复为显示（开关本身不持久化）
    public static void reset() {
        clearResults();
        hudEnabled = true;
    }

    /** 不可修改的批次快照；图标与派生量缓存只供渲染读取。 */
    public record Snapshot(List<Target> blocks, List<Target> containers, List<ItemGroup> groups,
                           int emptyCount, boolean range, long born, long expires, HudLayout hud) {
        public float alpha(long time) {
            if (expires <= time) return 0;
            return Math.min(1.0F, (expires - time) / (float) FADE_MS)
                    * Math.clamp((time - born) / 150.0F, 0.0F, 1.0F);
        }

        // 淡出尾部直接跳过绘制：HUD 汇总/详情与透视描边共用同一阈值（alpha * 255 < 4）。
        // 原版字体会把极低 alpha 强制改为不透明，所以不能只判 alpha <= 0。
        public boolean visible(long time) {
            return alpha(time) * 255.0F >= 4.0F;
        }
    }

    /** 可疑方块持有解析结果，容器只持有位置，不推测其内容。 */
    public record Target(BlockPos pos, ScanEntry entry, ItemStack icon) {
    }

    /** 一类展示物品及其总数量。 */
    public record ItemGroup(ItemStack icon, Component name, long count) {
    }

    /** 同名但来源或封存者不同的物品分开统计。 */
    private record GroupKey(String itemId, Component name, boolean sealed, String crafter) {
    }

    /** 汇总面板的派生布局与文本；文本已按面板宽度截断，绘制方只需落笔。 */
    public record SummaryLayout(int x, int y, int width, int height, String title,
                                @Nullable String message, String more, boolean footer,
                                List<GroupRow> groupRows) {
        public int rows() {
            return groupRows.size();
        }
    }

    /** 汇总面板中的一行分组：图标、名称与数量文本及已算好的绘制坐标。 */
    public record GroupRow(ItemGroup group, String nameText, int nameX, int nameWidth,
                           String countText, int countX, int countWidth, int y) {
    }

    /** 目标详情面板的派生布局与文本；距离行随玩家位置逐帧变化，只借用它的宽度参与几何决策。 */
    public record DetailLayout(int x, int y, int width, int height, int textOffset, int horizontalPadding,
                               boolean hasIcon, List<String> nameLines, @Nullable String sealedLine) {
        // 详情面板内每行可用的文本宽度
        public int lineWidth() {
            return width - horizontalPadding;
        }
    }

    /**
     * HUD 派生量缓存：翻译字符串、逐行文本、测量宽度与分组行布局。
     * <p>
     * 生命周期与所属 {@link Snapshot} 一致——扫描数据变化时随快照换新，tick 只延长显示时间时沿用同一实例，
     * 因此不会逐帧重算。缓存键是「语言实例 + 窗口尺寸」：资源重载会替换 {@link Language} 单例，
     * 身份比较即可廉价判定语言切换；窗口尺寸变化则重算面板几何。仅渲染线程访问，无需同步。
     */
    public static final class HudLayout {
        private Language language;
        private long viewport = Long.MIN_VALUE;
        @Nullable
        private SummaryLayout summary;
        @Nullable
        private Target detailTarget;
        private int detailKeyWidth = Integer.MIN_VALUE;
        private int detailKeyPositionWidth = Integer.MIN_VALUE;
        @Nullable
        private DetailLayout detail;

        // 语言实例或窗口尺寸变化时整体失效
        private void rekey(Language current, int screenWidth, int screenHeight) {
            long key = ((long) screenWidth << 32) | (screenHeight & 0xFFFFFFFFL);
            if (this.language == current && this.viewport == key) {
                return;
            }
            this.language = current;
            this.viewport = key;
            this.summary = null;
            this.detail = null;
            this.detailTarget = null;
            this.detailKeyWidth = Integer.MIN_VALUE;
            this.detailKeyPositionWidth = Integer.MIN_VALUE;
        }

        // 命中则返回汇总布局，未命中返回 null，由调用方重算后回填
        @Nullable
        public SummaryLayout summaryIfValid(Language current, int screenWidth, int screenHeight) {
            rekey(current, screenWidth, screenHeight);
            return summary;
        }

        public void putSummary(Language current, int screenWidth, int screenHeight, SummaryLayout value) {
            rekey(current, screenWidth, screenHeight);
            summary = value;
        }

        // 命中则返回详情布局；聚焦目标、可用宽度或距离行宽度变化都会未命中
        // （距离行宽度参与面板宽度决策，所以它变化时必须重算几何）
        @Nullable
        public DetailLayout detailIfValid(Language current, int screenWidth, int screenHeight,
                                          @Nullable Target target, int textWidth, int positionWidth) {
            rekey(current, screenWidth, screenHeight);
            if (target == null) {
                return null;
            }
            return target.equals(detailTarget) && detailKeyWidth == textWidth
                    && detailKeyPositionWidth == positionWidth ? detail : null;
        }

        public void putDetail(Language current, int screenWidth, int screenHeight, Target target,
                              int textWidth, int positionWidth, DetailLayout value) {
            rekey(current, screenWidth, screenHeight);
            detailTarget = target;
            detailKeyWidth = textWidth;
            detailKeyPositionWidth = positionWidth;
            detail = value;
        }
    }
}
