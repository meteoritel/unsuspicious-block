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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 整批扫描结果、地下目标选择和世界描边共用的客户端状态。 */
public final class ReaderScanHudState {
    private static final long DISPLAY_MS = 10_000L;
    private static final long MAX_DISPLAY_MS = 30_000L;
    private static final long FADE_MS = 500L;
    private static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), 0, false, 0, 0);
    private static volatile Snapshot current = EMPTY;
    private static volatile Target focused;
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
                emptyCount, range, born, expires);
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
            current = new Snapshot(snapshot.blocks(), snapshot.containers(), snapshot.groups(), snapshot.emptyCount(),
                    snapshot.range(), snapshot.born(), expires);
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

    public static void reset() {
        clearResults();
        hudEnabled = true;
    }

    /** 不可修改的批次快照；图标只供渲染读取。 */
    public record Snapshot(List<Target> blocks, List<Target> containers, List<ItemGroup> groups,
                           int emptyCount, boolean range, long born, long expires) {
        public float alpha(long time) {
            if (expires <= time) return 0;
            return Math.min(1.0F, (expires - time) / (float) FADE_MS)
                    * Math.clamp((time - born) / 150.0F, 0.0F, 1.0F);
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
}
