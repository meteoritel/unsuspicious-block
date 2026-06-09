package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.item.SuspiciousReaderItem;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import net.minecraft.server.level.ServerPlayer;

/** 扫描仪等级切换处理器 */
public final class ReaderScanLevelHandler {
    private ReaderScanLevelHandler() {}

    // 处理客户端同步的扫描仪扫描等级切换
    public static void handleUpdateReaderScanLevel(UpdateReaderScanLevelPayload payload, ServerPlayer player) {
        var stack = player.getMainHandItem();
        if (stack.getItem() == ModItems.SUSPICIOUS_READER) {
            SuspiciousReaderItem.setScanLevel(stack, payload.newLevel());
            return;
        }
        stack = player.getOffhandItem();
        if (stack.getItem() == ModItems.SUSPICIOUS_READER) {
            SuspiciousReaderItem.setScanLevel(stack, payload.newLevel());
        }
    }
}