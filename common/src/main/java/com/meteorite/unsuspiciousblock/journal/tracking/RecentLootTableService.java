package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 最近战利品表记录服务——只维护玩家侧的有限 ID 列表，不创建完整追踪会话。
 */
public final class RecentLootTableService {
    private RecentLootTableService() {
    }

    // 记录可管理的战利品表，并与管理页统一排除实体和方块掉落表
    public static void record(ServerPlayer player, ResourceLocation tableId) {
        if (player == null || tableId == null || !isSupported(tableId)) {
            return;
        }
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state != null) {
            state.recordRecentLootTable(tableId);
        }
    }

    // 判断战利品表是否属于当前管理功能支持的范围
    public static boolean isSupported(ResourceLocation tableId) {
        String path = tableId.getPath();
        return !path.startsWith("entities/") && !path.startsWith("blocks/");
    }
}
