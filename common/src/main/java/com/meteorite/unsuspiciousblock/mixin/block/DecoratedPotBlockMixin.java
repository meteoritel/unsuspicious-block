package com.meteorite.unsuspiciousblock.mixin.block;

import com.meteorite.unsuspiciousblock.blockentity.DecoratedPotLootState;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.DecoratedPotTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.DirectLootTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.RecentLootTableService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在陶罐内容被原版掉出前提交最终单物品栈。
 */
@Mixin(DecoratedPotBlock.class)
public abstract class DecoratedPotBlockMixin {

    @Inject(
            method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD")
    )
    private void unsuspiciousblock$recordPotLootBeforeRemoval(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston,
            CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)
                || movedByPiston
                || state.getBlock() == newState.getBlock()) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof DecoratedPotBlockEntity pot)
                || !(blockEntity instanceof DecoratedPotLootState potState)) {
            return;
        }

        // getTheItem 会在直接破坏路径中解析 loot table，返回后最小状态与最终物品均已确定
        ItemStack stack = pot.getTheItem().copy();
        ResourceLocation tableId = potState.unsuspiciousblock$getDecoratedPotLootTableName();
        ServerPlayer player = DecoratedPotTrackingService.resolvePlayer(
                serverLevel, pos, null, potState.unsuspiciousblock$getDecoratedPotPlayerUuid());
        RecentLootTableService.record(player, tableId);
        if (tableId != null && player != null && !stack.isEmpty()) {
            DirectLootTrackingService.submit(
                    player,
                    tableId,
                    LootSourceType.DECORATED_POT,
                    pos,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                    stack);
        }
        potState.unsuspiciousblock$clearDecoratedPotLootState();
    }
}
