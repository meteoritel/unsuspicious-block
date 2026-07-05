package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import com.mojang.datafixers.util.Either;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 拦截 NestedLootTable 的物品生成，在追踪上下文激活时自动捕获嵌套子表物品并发布追踪事件。
 * <p>
 * 原版部分战利品表（如 {@code minecraft:gameplay/fishing}）通过 {@link NestedLootTable}
 * 引用子表（fish/junk/treasure）。各追踪入口（钓鱼 / 开箱 / 考古刷拭等）在调用
 * {@code LootTable.getRandomItems} 前向 {@link LootTrackingContextHolder} 压入上下文，
 * 本 Mixin 读取上下文并在子表 {@code getRandomItemsRaw} 调用处包装 Consumer 收集物品，
 * 通过 {@link LootTrackingEvents} 发布携带完整 tableStack 链路的事件。
 * <p>
 * 上下文为空时（如模拟器主线程模拟、非追踪场景）直接透传，零开销。
 * 仅处理 ResourceKey 引用的子表；内联表无独立 ID 不参与追踪。
 * <p>
 * 批量发布：一次子表 {@code getRandomItemsRaw} 调用收集的所有物品合并为单次 publish，
 * 使订阅者创建一条日志条目（"一次嵌套表发现 = 一条 ExcavationLogEntry"）。
 */
@Mixin(NestedLootTable.class)
public abstract class NestedLootTableMixin {

    @Shadow
    @Final
    private Either<ResourceKey<LootTable>, LootTable> contents;

    // 包装 getRandomItemsRaw 的 Consumer，在追踪上下文激活时收集子表物品后批量发布追踪事件
    @WrapOperation(
            method = "createItemStack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V")
    )
    private void unsuspiciousblock$trackNestedLoot(
            LootTable lootTable, LootContext lootContext, Consumer<ItemStack> consumer,
            Operation<Void> original) {
        // 仅处理 ResourceKey 引用的子表；内联表无独立 ID 不参与追踪
        Optional<ResourceKey<LootTable>> leftOpt = this.contents.left();
        if (leftOpt.isEmpty()) {
            original.call(lootTable, lootContext, consumer);
            return;
        }
        ResourceLocation childTableId = leftOpt.get().location();

        // 读取追踪上下文；为空（模拟器 / 非追踪场景）直接透传
        LootTrackingContext ctx = LootTrackingContextHolder.current();
        if (ctx == null) {
            original.call(lootTable, lootContext, consumer);
            return;
        }

        // 子表必须命中追踪规则，避免误捕无关嵌套表（如非考古路径的 loot_table 引用）
        if (!LootTableNames.isArchaeologyLootTable(childTableId)
                && !childTableId.equals(ctx.rootTableId())) {
            original.call(lootTable, lootContext, consumer);
            return;
        }

        // 派生子上下文并压栈；包装 Consumer 收集子表物品
        LootTrackingContext childCtx = ctx.descend(childTableId);
        LootTrackingContextHolder.push(childCtx);
        List<ItemStack> captured = new ArrayList<>();
        Consumer<ItemStack> wrappedConsumer = stack -> {
            if (!stack.isEmpty()) {
                captured.add(stack);
            }
            consumer.accept(stack);
        };
        try {
            original.call(lootTable, lootContext, wrappedConsumer);
        } finally {
            LootTrackingContextHolder.pop();
        }

        if (captured.isEmpty()) {
            return;
        }
        // 批量发布：将本次子表收集的所有物品按签名合并为单次 publish，
        // 使订阅者创建一条日志条目（"一次嵌套表发现 = 一条 ExcavationLogEntry"）
        Map<String, Integer> itemCounts = new HashMap<>();
        for (ItemStack stack : captured) {
            LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(childCtx.rootTableId(), stack);
            if (signature != null) {
                itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
            }
        }
        if (!itemCounts.isEmpty()) {
            LootTrackingEvents.publish(childCtx, itemCounts);
        }
    }
}
