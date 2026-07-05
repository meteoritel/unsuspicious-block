package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 战利品追踪上下文——描述当前追踪的根表与子表链路。
 * <p>
 * 由追踪入口（钓鱼 / 开箱 / 考古刷拭等）在调用 {@code LootTable.getRandomItems} 前写入
 * {@link LootTrackingContextHolder}，供 {@link com.meteorite.unsuspiciousblock.mixin.interaction.NestedLootTableMixin}
 * 识别追踪上下文并自动捕获嵌套子表物品。
 * <p>
 * 字段说明：
 * <ul>
 *   <li>{@code rootTableId}：根表 ID（catalog 中注册的表），签名解析锚定此表保证精度</li>
 *   <li>{@code tableStack}：从根表到当前层级的完整链路（含 rootTableId），子表 Mixin 通过 {@link #descend} 派生新上下文</li>
 *   <li>{@code pos}：触发本次追踪的位置（鱼漂 / 容器 / 方块），用于构造日志条目的 ExcavationContext</li>
 *   <li>{@code sourceBlockId}：源方块 ID（考古刷拭场景有值，钓鱼 / 开箱场景为 null）</li>
 * </ul>
 * 不可变，线程局部（ThreadLocal）使用，无需同步。
 */
public record LootTrackingContext(
        ServerPlayer player,
        ResourceLocation rootTableId,
        LootSourceType lootSource,
        long gameTime,
        long dayTime,
        List<ResourceLocation> tableStack,
        BlockPos pos,
        @Nullable ResourceLocation sourceBlockId
) {
    public LootTrackingContext {
        if (player == null || rootTableId == null || lootSource == null) {
            throw new IllegalArgumentException("LootTrackingContext 必须包含非 null 的 player/rootTableId/lootSource");
        }
        tableStack = tableStack == null ? List.of(rootTableId) : List.copyOf(tableStack);
        if (pos == null) {
            pos = BlockPos.ZERO;
        }
    }

    // 创建根上下文（tableStack 仅含 rootTableId）
    public static LootTrackingContext root(ServerPlayer player, ResourceLocation rootTableId,
                                           LootSourceType lootSource, long gameTime, long dayTime,
                                           BlockPos pos, @Nullable ResourceLocation sourceBlockId) {
        return new LootTrackingContext(player, rootTableId, lootSource, gameTime, dayTime,
                List.of(rootTableId), pos, sourceBlockId);
    }

    // 派生子上下文：tableStack 追加 childTableId，rootTableId 与其余字段保持不变
    public LootTrackingContext descend(ResourceLocation childTableId) {
        if (childTableId == null) {
            return this;
        }
        List<ResourceLocation> newStack = new ArrayList<>(this.tableStack.size() + 1);
        newStack.addAll(this.tableStack);
        newStack.add(childTableId);
        return new LootTrackingContext(this.player, this.rootTableId, this.lootSource,
                this.gameTime, this.dayTime, newStack, this.pos, this.sourceBlockId);
    }

    // 返回当前层级的表 ID（tableStack 末尾元素）
    public ResourceLocation currentTableId() {
        return this.tableStack.isEmpty() ? this.rootTableId : this.tableStack.getLast();
    }
}
