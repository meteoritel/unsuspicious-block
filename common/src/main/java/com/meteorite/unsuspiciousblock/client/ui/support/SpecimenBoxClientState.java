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

/** 标本箱客户端菜单快照——只保存当前打开菜单需要的权威视图。 */
public final class SpecimenBoxClientState {
    private static final Map<Integer, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private SpecimenBoxClientState() {
    }

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
    public static Snapshot getSnapshot(int containerId) {
        return SNAPSHOTS.get(containerId);
    }

    public static void clear(int containerId) {
        SNAPSHOTS.remove(containerId);
    }

    public static void clearAll() {
        SNAPSHOTS.clear();
    }

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
