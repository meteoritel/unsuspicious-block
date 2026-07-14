package com.meteorite.unsuspiciousblock.mixin.container;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在可随机生成战利品的容器解析时接入考古笔记追踪。
 */
@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {

    // 在解析前捕获本次容器将要使用的战利品表标识，并 push 追踪上下文（若命中追踪规则）
    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$captureLootTable(Player player,
                                                    CallbackInfo ci,
                                                    @Share("capturedLootTable") LocalRef<ResourceLocation> capturedLootTable,
                                                    @Share("ctxPushed") LocalRef<Boolean> ctxPushed) {
        ResourceLocation tableId = null;
        ctxPushed.set(false);
        // 异常恢复：上次 unpackLootTable 异常时 TAIL 未触发 pop，清理残留上下文
        LootTrackingContextHolder.clear();
        if (player instanceof ServerPlayer sp) {
            RandomizableContainer container = (RandomizableContainer) this;
            Level level = container.getLevel();
            ResourceKey<LootTable> lootTable = container.getLootTable();
            if (lootTable != null && level != null && !level.isClientSide() && level.getServer() != null) {
                tableId = lootTable.location();
            }
            // 命中追踪规则时 push 上下文，使 NestedLootTableMixin 自动捕获嵌套子表
            if (tableId != null && LootTableNames.isArchaeologyLootTable(tableId) && level instanceof ServerLevel serverLevel) {
                // 容器位置与源方块：方块容器有 BlockEntity 位置，非方块容器回退到 ZERO / null
                BlockPos pos = BlockPos.ZERO;
                ResourceLocation sourceBlockId = null;
                if (container instanceof BlockEntity blockEntity) {
                    pos = blockEntity.getBlockPos();
                    sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
                }
                LootTrackingContext ctx = LootTrackingContext.root(
                        sp, tableId, LootSourceType.LOOT_CONTAINER,
                        serverLevel.getGameTime(), serverLevel.getDayTime(), pos, sourceBlockId);
                LootTrackingContextHolder.push(ctx);
                ctxPushed.set(true);
            }
        }

        capturedLootTable.set(tableId);
    }

    // 在解析后根据容器实际生成的物品更新运行时追踪状态，并 pop 追踪上下文
    @Inject(method = "unpackLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$trackResolvedLoot(Player player,
                                                     CallbackInfo ci,
                                                     @Share("capturedLootTable") LocalRef<ResourceLocation> capturedLootTable,
                                                     @Share("ctxPushed") LocalRef<Boolean> ctxPushed) {
        // pop 追踪上下文（若 push 了）；try-finally 语义——unpackLootTable 内部异常时 TAIL 不触发，
        // 但下次 HEAD 会先重置 ctxPushed，且主线程串行调用不会跨容器泄漏
        if (Boolean.TRUE.equals(ctxPushed.get())) {
            LootTrackingContextHolder.pop();
            ctxPushed.set(false);
        }

        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        if (!(this instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }

        ResourceLocation tableId = capturedLootTable.get();
        if (tableId == null) {
            return;
        }

        List<LootResultSignature> candidates = unsuspiciousblock$collectCandidates(trackedContainer, tableId);
        ContainerTrackingService.onContainerLootResolved(sp, trackedContainer,
                tableId,
                trackedContainer.unsuspiciousblock$collectContainerItemCounts(candidates));
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
