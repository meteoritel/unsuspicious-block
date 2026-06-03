package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.network.payload.SyncSpecimenBoxViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标本箱客户端菜单快照——服务端推送的权威视图的客户端缓存。
 * 以容器 ID（containerId）为索引保存每个打开菜单的 Snapshot，
 * 供 SpecimenBoxScreen 读取纯渲染所需数据（目录、逻辑槽位、页码）。
 */
public final class SpecimenBoxClientState {
    private static final Map<Integer, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private SpecimenBoxClientState() {
    }

    // 接收服务端推送的权威视图，按 containerId 缓存
    public static void receiveView(SyncSpecimenBoxViewPayload payload) {
        List<TableView> tables = new ArrayList<>(payload.tables().size());
        for (SyncSpecimenBoxViewPayload.TableEntry table : payload.tables()) {
            tables.add(new TableView(table.tableId(), table.displayName()));
        }

        List<LogicalSlotView> logicalSlots = new ArrayList<>(payload.logicalSlots().size());
        for (SyncSpecimenBoxViewPayload.LogicalSlotEntry slot : payload.logicalSlots()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(slot.signatureKey());
            logicalSlots.add(new LogicalSlotView(slot.displayName(), signature, slot.unlocked(),
                    slot.journalCount(), slot.storedCount()));
        }

        SNAPSHOTS.put(payload.containerId(), new Snapshot(
                List.copyOf(tables),
                payload.selectedTableIndex(),
                payload.pageIndex(),
                Math.max(1, payload.pageCount()),
                List.copyOf(logicalSlots)
        ));
    }

    @Nullable
    // 获取指定容器 ID 的快照
    public static Snapshot getSnapshot(int containerId) {
        return SNAPSHOTS.get(containerId);
    }

    // 清除指定容器的快照
    public static void clear(int containerId) {
        SNAPSHOTS.remove(containerId);
    }

    // 清除所有容器的快照
    public static void clearAll() {
        SNAPSHOTS.clear();
    }

    // 客户端 tick：当玩家退出世界时自动清理所有快照
    public static void tick() {
        if (SNAPSHOTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clearAll();
        }
    }

    public record Snapshot(List<TableView> tables, int selectedTableIndex, int pageIndex,
                           int pageCount, List<LogicalSlotView> logicalSlots) {
    }

    public record TableView(ResourceLocation tableId, Component displayName) {
    }

    public record LogicalSlotView(Component displayName, @Nullable LootResultSignature signature,
                                  boolean unlocked, int journalCount, int storedCount) {
    }
}
