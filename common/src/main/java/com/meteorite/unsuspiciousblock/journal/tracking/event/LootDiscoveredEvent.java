package com.meteorite.unsuspiciousblock.journal.tracking.event;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 战利品发现事件。
 * <p>
 * 由各追踪入口（考古、钓鱼、战利品箱等）在玩家获得战利品时通过 {@link LootTrackingEvents#publish} 发布。
 * 订阅者按优先级顺序同步处理：解锁日记状态 → 记录首次发现 → 创建日志条目 → 检查成就。
 * <p>
 * 物品统一为 {@code Map<String, Integer>} 形式（signature storedKey → count），
 * 单物品栈在 publish 时由事件总线内部调用 {@code resolveSignature} 转换。
 * state 字段在 publish 时一次性预解析，避免订阅者重复 cast。
 * <p>
 * 嵌套表场景下：
 * <ul>
 *   <li>{@code rootTableId} 为根表 ID（catalog 中注册的表），签名解析锚定此表保证精度</li>
 *   <li>{@code tableId} 为当前层级的表 ID（可能是子表）</li>
 *   <li>{@code tableStack} 为从根表到当前层级的完整链路，订阅者遍历此链路对每个在 catalog 中的表更新解锁状态</li>
 *   <li>{@code pos} 为触发本次追踪的位置（鱼漂 / 容器 / 方块），用于构造日志条目</li>
 *   <li>{@code sourceBlockId} 为源方块 ID（考古刷拭场景有值，钓鱼 / 开箱为 null）</li>
 * </ul>
 */
public record LootDiscoveredEvent(ServerPlayer player, ResourceLocation rootTableId, ResourceLocation tableId,
                                  List<ResourceLocation> tableStack, LootSourceType lootSource,
                                  long gameTime, long dayTime, Map<String, Integer> itemCounts,
                                  BlockPos pos, @Nullable ResourceLocation sourceBlockId,
                                  @Nullable ArchaeologyJournalState state,
                                  @Nullable Consumer<ExcavationLogEntry> pendingEntryConsumer) {
    public LootDiscoveredEvent(ServerPlayer player, ResourceLocation rootTableId, ResourceLocation tableId,
                               List<ResourceLocation> tableStack, LootSourceType lootSource,
                               long gameTime, long dayTime, Map<String, Integer> itemCounts,
                               BlockPos pos, @Nullable ResourceLocation sourceBlockId,
                               @Nullable ArchaeologyJournalState state,
                               @Nullable Consumer<ExcavationLogEntry> pendingEntryConsumer) {
        this.player = player;
        this.rootTableId = rootTableId;
        this.tableId = tableId;
        this.tableStack = List.copyOf(tableStack);
        this.lootSource = lootSource;
        this.gameTime = gameTime;
        this.dayTime = dayTime;
        this.itemCounts = Map.copyOf(itemCounts);
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.sourceBlockId = sourceBlockId;
        this.state = state;
        this.pendingEntryConsumer = pendingEntryConsumer;
    }

    // 兼容旧调用方的便利构造器（带 pendingEntryConsumer）：rootTableId=tableId, tableStack=[tableId], pos=ZERO, sourceBlockId=null
    public LootDiscoveredEvent(ServerPlayer player, ResourceLocation tableId, LootSourceType lootSource,
                               long gameTime, long dayTime, Map<String, Integer> itemCounts,
                               @Nullable ArchaeologyJournalState state,
                               @Nullable Consumer<ExcavationLogEntry> pendingEntryConsumer) {
        this(player, tableId, tableId, List.of(tableId), lootSource, gameTime, dayTime, itemCounts,
                BlockPos.ZERO, null, state, pendingEntryConsumer);
    }
}
