package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 已确定归属玩家与最终物品时的即时战利品提交服务。
 * 一次提交统一完成表和物品解锁、获得数量累加、日志写入及后续奖励检查。
 */
public final class DirectLootTrackingService {
    private DirectLootTrackingService() {
    }

    // 将一个已经确定的最终物品栈作为立即获得事件提交
    public static boolean submit(ServerPlayer player,
                                 ResourceLocation tableId,
                                 LootSourceType sourceType,
                                 BlockPos pos,
                                 @Nullable ResourceLocation sourceBlockId,
                                 ItemStack stack) {
        if (stack.isEmpty()
                || !ArchaeologyJournalServerCatalog.isTrackedTable(tableId)
                || ArchaeologyJournalStateHolder.getState(player) == null) {
            return false;
        }

        LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(tableId, stack);
        if (signature == null) {
            signature = LootResultSignature.plain(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }

        ServerLevel level = player.serverLevel();
        LootTrackingContext context = LootTrackingContext.root(
                player, tableId, sourceType, level.getGameTime(), level.getDayTime(), pos, sourceBlockId);
        LootSession session = new LootSession(context);
        LootTrackingEvents.submit(session,
                Map.of(signature.toStoredKey(), stack.getCount()),
                LootSettlementStrategies.immediate());
        return true;
    }
}
