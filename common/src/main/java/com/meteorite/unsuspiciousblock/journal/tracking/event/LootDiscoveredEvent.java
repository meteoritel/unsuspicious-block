package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 战利品发现事件。
 * <p>
 * 由各追踪入口（考古、钓鱼、战利品箱等）在玩家获得战利品时通过 {@link LootTrackingEvents#publish} 发布。
 * 订阅者按优先级顺序同步处理：解锁日记状态 → 记录首次发现 → 检查成就。
 * <p>
 * 物品统一为 {@code Map<String, Integer>} 形式（signature storedKey → count），
 * 单物品栈在 publish 时由事件总线内部调用 {@code resolveSignature} 转换。
 * state 字段在 publish 时一次性预解析，避免订阅者重复 cast。
 */
public record LootDiscoveredEvent(ServerPlayer player, ResourceLocation tableId, LootSourceType lootSource,
                                  long gameTime, long dayTime, Map<String, Integer> itemCounts,
                                  @Nullable ArchaeologyJournalState state) {
    public LootDiscoveredEvent(ServerPlayer player, ResourceLocation tableId,
                               LootSourceType lootSource, long gameTime, long dayTime,
                               Map<String, Integer> itemCounts,
                               @Nullable ArchaeologyJournalState state) {
        this.player = player;
        this.tableId = tableId;
        this.lootSource = lootSource;
        this.gameTime = gameTime;
        this.dayTime = dayTime;
        this.itemCounts = Map.copyOf(itemCounts);
        this.state = state;
    }
}
