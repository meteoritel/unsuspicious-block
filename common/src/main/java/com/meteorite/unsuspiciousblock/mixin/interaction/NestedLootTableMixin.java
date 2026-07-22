package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
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
 * 拦截 NestedLootTable 的物品生成，在追踪上下文激活时自动捕获嵌套子表物品。
 * <p>
 * 原版部分战利品表（如 {@code minecraft:gameplay/fishing}）通过 {@link NestedLootTable}
 * 引用子表（fish/junk/treasure）。各追踪入口（钓鱼 / 开箱 / 考古刷拭等）在调用
 * {@code LootTable.getRandomItems} 前通过 {@link LootTrackingContextHolder} 建立上下文作用域，
 * 本 Mixin 读取上下文并在子表 {@code getRandomItemsRaw} 调用处包装 Consumer 收集物品，
 * 将结果汇入当前 {@link LootSession}，由根入口在生成完成后统一提交。
 * <p>
 * 上下文为空时（如模拟器主线程模拟、非追踪场景）直接透传，零开销。
 * 仅处理 ResourceKey 引用的子表；内联表无独立 ID 不参与追踪。
 * <p>
 * 同一次子表 {@code getRandomItemsRaw} 调用收集的物品会先按签名合并，再追加到当前会话。
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
        LootSession session = LootTrackingContextHolder.currentSession();
        if (ctx == null || session == null) {
            original.call(lootTable, lootContext, consumer);
            return;
        }

        // 子表必须命中追踪规则，避免误捕无关嵌套表（如非考古路径的 loot_table 引用）
        if (!ArchaeologyJournalServerCatalog.isTrackedTable(childTableId)
                && !childTableId.equals(ctx.rootTableId())) {
            original.call(lootTable, lootContext, consumer);
            return;
        }

        // 派生子上下文并压栈；包装 Consumer 收集子表物品
        LootTrackingContext childCtx = ctx.descend(childTableId);
        List<ItemStack> captured = new ArrayList<>();
        Consumer<ItemStack> wrappedConsumer = stack -> {
            if (!stack.isEmpty()) {
                captured.add(stack);
            }
            consumer.accept(stack);
        };
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(childCtx)) {
            original.call(lootTable, lootContext, wrappedConsumer);
        }

        if (captured.isEmpty()) {
            return;
        }
        // 将本次子表收集的所有物品按签名合并后追加到当前会话
        Map<String, Integer> itemCounts = new HashMap<>();
        for (ItemStack stack : captured) {
            LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(childCtx.rootTableId(), stack);
            if (signature != null) {
                itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
            }
        }
        if (!itemCounts.isEmpty()) {
            session.capture(childCtx, itemCounts);
        }
    }
}
