package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.data.DefaultLootFiller;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 在 Lootr 的 DefaultLootFiller 解析战利品表时接入考古笔记追踪。
 * <p>
 * Lootr 绕过原版 {@code RandomizableContainer#unpackLootTable}，走自己的
 * {@code DefaultLootFiller#unpackLootTable} -> {@code LootTable#fill} 路径。
 * 本 Mixin 在该方法的 HEAD/TAIL 注入，与原版 {@code RandomizableContainerMixin} 对称：
 * HEAD 捕获战利品表标识并 push 追踪上下文，TAIL 收集物品并提交追踪服务。
 */
// Lootr 兼容必须引用其 @ApiStatus.Internal 的 ILootrInfo API（getInfoLootTable / isInfoReferenceInventory / getInfoLevel 等），无公开替代
@SuppressWarnings("UnstableApiUsage")
@Mixin(DefaultLootFiller.class)
public abstract class DefaultLootFillerMixin {

    // 在解析前捕获本次容器将要使用的战利品表标识，并 push 追踪上下文（若命中追踪规则）
    @Inject(method = "unpackLootTable", at = @At("HEAD"), remap = false)
    private void unsuspiciousblock$captureLootTable(ILootrInfoProvider provider, Player player, Container inventory,
                                                    CallbackInfo ci,
                                                    @Share("capturedLootTable") LocalRef<ResourceLocation> capturedLootTable,
                                                    @Share("capturedPos") LocalRef<BlockPos> capturedPos,
                                                    @Share("capturedSourceBlockId") LocalRef<ResourceLocation> capturedSourceBlockId,
                                                    @Share("ctxPushed") LocalRef<Boolean> ctxPushed) {
        capturedLootTable.set(null);
        ctxPushed.set(false);

        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        ResourceKey<LootTable> lootTableKey = provider.getInfoLootTable();
        if (lootTableKey == null) {
            return;
        }
        // 引用库存复制路径不走战利品表滚动，跳过追踪
        if (provider.isInfoReferenceInventory()) {
            return;
        }

        Level level = provider.getInfoLevel();
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ResourceLocation tableId = lootTableKey.location();
        if (!LootTableNames.isArchaeologyLootTable(tableId)) {
            return;
        }

        capturedLootTable.set(tableId);

        // 解析容器位置与源方块
        BlockPos pos = provider.getInfoPos();
        capturedPos.set(pos);

        ResourceLocation sourceBlockId = null;
        Container infoContainer = provider.getInfoContainer();
        if (infoContainer instanceof BlockEntity blockEntity) {
            sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }
        capturedSourceBlockId.set(sourceBlockId);

        // push 追踪上下文，使 NestedLootTableMixin 自动捕获嵌套子表
        LootTrackingContext ctx = LootTrackingContext.root(
                sp, tableId, LootSourceType.LOOT_CONTAINER,
                serverLevel.getGameTime(), serverLevel.getDayTime(),
                pos, sourceBlockId);
        LootTrackingContextHolder.push(ctx);
        ctxPushed.set(true);
    }

    // 在解析后根据容器实际生成的物品更新运行时追踪状态，并 pop 追踪上下文
    @Inject(method = "unpackLootTable", at = @At("TAIL"), remap = false)
    private void unsuspiciousblock$trackResolvedLoot(ILootrInfoProvider provider, Player player, Container inventory,
                                                     CallbackInfo ci,
                                                     @Share("capturedLootTable") LocalRef<ResourceLocation> capturedLootTable,
                                                     @Share("capturedPos") LocalRef<BlockPos> capturedPos,
                                                     @Share("capturedSourceBlockId") LocalRef<ResourceLocation> capturedSourceBlockId,
                                                     @Share("ctxPushed") LocalRef<Boolean> ctxPushed) {
        // pop 追踪上下文（若 push 了）
        if (Boolean.TRUE.equals(ctxPushed.get())) {
            LootTrackingContextHolder.pop();
            ctxPushed.set(false);
        }

        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        ResourceLocation tableId = capturedLootTable.get();
        if (tableId == null) {
            return;
        }

        if (!(inventory instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }

        List<LootResultSignature> candidates = unsuspiciousblock$collectCandidates(trackedContainer, tableId);
        ContainerTrackingService.onContainerLootResolved(sp, trackedContainer, tableId,
                trackedContainer.unsuspiciousblock$collectContainerItemCounts(candidates),
                capturedPos.get(), capturedSourceBlockId.get());
    }

    // 为当前容器生成用于运行时匹配的签名候选；目录缺失时退回到容器现状的普通物品签名
    @Unique
    private static List<LootResultSignature> unsuspiciousblock$collectCandidates(TrackedContainerLootState trackedContainer,
                                                                                 ResourceLocation tableId) {
        TableDefinition table = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        if (table != null && !table.items().isEmpty()) {
            List<LootResultSignature> candidates = new ArrayList<>();
            for (ItemDefinition item : table.items()) {
                candidates.add(item.signature());
            }
            return candidates;
        }

        LinkedHashSet<ResourceLocation> fallbackItems = new LinkedHashSet<>();
        for (int slot = 0; slot < trackedContainer.getContainerSize(); slot++) {
            ItemStack stack = trackedContainer.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            fallbackItems.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }

        List<LootResultSignature> fallbackCandidates = new ArrayList<>();
        for (ResourceLocation itemId : fallbackItems) {
            fallbackCandidates.add(LootResultSignature.plain(itemId));
        }
        return fallbackCandidates;
    }
}
