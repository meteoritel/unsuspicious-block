package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可疑方块刷拭掉落流程的辅助工具。
 * <p>
 * 将原 {@code BrushableBlockEntityMixin.dropContent} 注入中散落的精掘翻倍 dispatch、
 * 额外物品弹出（原版位置公式）与考古日志结算集中至此，Mixin 仅作为入口。
 */
public final class BrushableLootDropHelper {

    private BrushableLootDropHelper() {
    }

    /**
     * 单次刷拭掉落的上下文快照，由 Mixin 在 dropContent HEAD 阶段构造。
     */
    public record DropContext(
            ServerPlayer player,
            BlockPos blockPos,
            Level level,
            @Nullable Direction hitDirection,
            ItemStack brushTool,
            long brushGameTime,
            long brushDayTime,
            @Nullable ResourceLocation lootTableName,
            @Nullable ExcavationLogEntry pendingEntry
    ) {
    }

    /**
     * 精掘翻倍的结果。
     *
     * @param rolledItem       翻倍后的主物品（写入 BlockEntity.item）
     * @param extraDrops       额外弹出的副本列表（与主物品分开弹出）
     * @param totalTrackedItem 用于考古日志结算的总追踪物品（主物品 + 额外中相同物品合并）
     */
    public record DropResult(
            ItemStack rolledItem,
            List<ItemStack> extraDrops,
            ItemStack totalTrackedItem
    ) {
    }

    /**
     * 在掉落开始时抽取精掘附魔翻倍，返回翻倍后的主物品与额外副本。
     * <p>
     * 时序在记录考古笔记物品数量之前，确保日志期望值保留原战利品表结果，
     * 实际值按翻倍后总数结算。
     */
    public static DropResult rollBrushItemDrop(DropContext ctx, ItemStack originalItem) {
        TriggerContext peCtx = TriggerContext.builder(ctx.player(), ctx.player().serverLevel())
                .pos(ctx.blockPos())
                .tool(ctx.brushTool())
                .build();
        List<ItemStack> drops = EnchantmentManager.dispatchValue(
                TriggerType.BRUSH_ITEM_DROP, peCtx, List.of(originalItem));

        // dispatch 返回空列表视为附魔未触发，保持原物品
        if (drops.isEmpty()) {
            return new DropResult(originalItem, List.of(), originalItem);
        }

        ItemStack main = drops.getFirst();
        List<ItemStack> extra = drops.size() > 1
                ? new ArrayList<>(drops.subList(1, drops.size()))
                : List.of();

        // 合并主物品与额外副本中相同物品的计数，作为日志结算总数
        ItemStack total = main.copy();
        for (ItemStack e : extra) {
            if (!e.isEmpty() && ItemStack.isSameItemSameComponents(total, e)) {
                total.grow(e.getCount());
            }
        }
        return new DropResult(main, extra, total);
    }

    /**
     * 按原版 dropContent 的位置公式弹出额外战利品副本。
     * <p>
     * 公式复制自 { net.minecraft.world.level.block.entity.BrushableBlockEntity#dropContent}，
     * 集中于此以便原版改动时单点维护。
     */
    public static void spawnExtraDrops(DropContext ctx, List<ItemStack> extraDrops) {
        if (extraDrops.isEmpty() || ctx.level() == null) {
            return;
        }
        double d0 = EntityType.ITEM.getWidth();
        double d1 = 1.0 - d0;
        double d2 = d0 / 2.0;
        Direction direction = Objects.requireNonNullElse(ctx.hitDirection(), Direction.UP);
        BlockPos blockpos = ctx.blockPos().relative(direction, 1);
        double d3 = blockpos.getX() + 0.5 * d1 + d2;
        double d4 = blockpos.getY() + 0.5 + (EntityType.ITEM.getHeight() / 2.0F);
        double d5 = blockpos.getZ() + 0.5 * d1 + d2;
        for (ItemStack extra : extraDrops) {
            if (!extra.isEmpty()) {
                ItemEntity itemEntity = new ItemEntity(ctx.level(), d3, d4, d5, extra.copy());
                itemEntity.setDeltaMovement(Vec3.ZERO);
                ctx.level().addFreshEntity(itemEntity);
            }
        }
    }

    /**
     * 使用翻倍后的总计数结算待定日志条目，确保考古笔记日志的 actualLoot 反映翻倍后的实际获取量。
     */
    public static void settleJournal(DropContext ctx, ItemStack totalTrackedItem) {
        if (ctx.lootTableName() == null || ctx.pendingEntry() == null) {
            return;
        }
        long gameTime = ctx.brushGameTime() >= 0L
                ? ctx.brushGameTime()
                : ctx.player().serverLevel().getGameTime();
        long dayTime = ctx.brushDayTime() >= 0L
                ? ctx.brushDayTime()
                : ctx.player().serverLevel().getDayTime();
        ArchaeologyLootRuntimeTracker.applyPendingLoot(ctx.player(), ctx.lootTableName(),
                ctx.pendingEntry(), totalTrackedItem, gameTime, dayTime);
    }
}
