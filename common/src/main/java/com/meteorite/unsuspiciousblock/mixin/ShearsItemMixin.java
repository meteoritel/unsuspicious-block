package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.enchantment.shears.TextileRecoveryService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShearsItem.class)
public abstract class ShearsItemMixin {
    @Inject(method = "mineBlock", at = @At("TAIL"))
    private void unsuspiciousblock$dropExtraLeafLoot(ItemStack stack, Level level, BlockState state,
                                                     BlockPos pos, LivingEntity miningEntity,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (miningEntity instanceof Player player) {
            TextileRecoveryService.onLeavesDestroyed(level, player, pos, state, stack);
        }
    }
}
