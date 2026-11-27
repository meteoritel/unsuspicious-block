package com.meteorite.unsuspiciousblock.platform.mixin.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.block.RainyCarrotEasterEgg;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Fabric 端方块掉落钩子——补齐 Fabric 原生事件缺失的方块掉落物修改入口，
 * 将成熟胡萝卜雨天彩蛋的掉落替换委托给 {@link RainyCarrotEasterEgg}。
 */
@Mixin(Block.class)
public abstract class BlockDropResourcesMixin {

    @WrapOperation(
            method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;")
    )
    private static List<ItemStack> unsuspiciousblock$applyRainyCarrotEasterEgg(
            BlockState state, ServerLevel level, BlockPos pos, @Nullable BlockEntity blockEntity,
            @Nullable Entity breaker, ItemStack tool, Operation<List<ItemStack>> original) {
        List<ItemStack> drops = original.call(state, level, pos, blockEntity, breaker, tool);
        return RainyCarrotEasterEgg.tryApplyToStacks(level, pos, state, breaker, tool, drops);
    }
}
