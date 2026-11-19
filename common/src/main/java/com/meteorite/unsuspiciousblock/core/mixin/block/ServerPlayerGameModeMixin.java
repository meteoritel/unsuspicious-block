package com.meteorite.unsuspiciousblock.core.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.meteorite.unsuspiciousblock.core.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.core.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.core.enchantment.framework.trigger.TriggerType;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 把移除前的自然骨块标记交给成功采掘回调。 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState unsuspiciousblock$captureNaturalBone(Block block, Level level, BlockPos pos,
            BlockState state, Player player, Operation<BlockState> original,
            @Share("naturalBone") LocalBooleanRef naturalBone) {
        naturalBone.set(state.is(Blocks.BONE_BLOCK)
                && NaturalBoneBlockTracker.isNatural((ServerLevel) level, pos));
        return original.call(block, level, pos, state, player);
    }

    @WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;playerDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void unsuspiciousblock$harvestNaturalBone(Block block, Level level, Player player, BlockPos pos,
            BlockState state, BlockEntity blockEntity, ItemStack tool, Operation<Void> original,
            @Share("naturalBone") LocalBooleanRef naturalBone) {
        original.call(block, level, player, pos, state, blockEntity, tool);
        if (naturalBone.get()) {
            EnchantmentManager.dispatch(TriggerType.BLOCK_BREAK, TriggerContext.builder((ServerPlayer) player,
                            (ServerLevel) level)
                    .pos(pos).blockState(state).tool(tool).build());
        }
    }
}
