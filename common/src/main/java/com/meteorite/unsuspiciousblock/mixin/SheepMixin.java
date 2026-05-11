package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.enchantment.shears.TextileRecoveryService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Sheep.class)
public abstract class SheepMixin {
    @Unique
    private ItemStack unsuspiciousblock$shears = ItemStack.EMPTY;

    @Inject(method = "mobInteract", at = @At("HEAD"))
    private void unsuspiciousblock$captureShears(Player player, InteractionHand hand,
                                                 CallbackInfoReturnable<InteractionResult> cir) {
        Sheep sheep = (Sheep) (Object) this;
        ItemStack itemInHand = player.getItemInHand(hand);
        this.unsuspiciousblock$shears = sheep.readyForShearing() && itemInHand.is(Items.SHEARS)
                ? itemInHand
                : ItemStack.EMPTY;
    }

    @Inject(method = "shear", at = @At("TAIL"))
    private void unsuspiciousblock$dropExtraString(SoundSource soundSource, CallbackInfo ci) {
        Sheep sheep = (Sheep) (Object) this;
        if (sheep.level() instanceof ServerLevel serverLevel && !this.unsuspiciousblock$shears.isEmpty()) {
            TextileRecoveryService.onSheepSheared(serverLevel, sheep, this.unsuspiciousblock$shears);
        }
    }

    @Inject(method = "mobInteract", at = @At("RETURN"))
    private void unsuspiciousblock$clearShears(Player player, InteractionHand hand,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        this.unsuspiciousblock$shears = ItemStack.EMPTY;
    }
}
