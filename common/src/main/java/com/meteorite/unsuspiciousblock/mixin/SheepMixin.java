package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * 在绵羊剪毛流程中接入织物回收附魔效果。
 */
@Mixin(Sheep.class)
public abstract class SheepMixin {
    @Unique
    private ItemStack unsuspiciousblock$shears = ItemStack.EMPTY;

    @Unique
    private Player unsuspiciousblock$interactPlayer;

    // 在交互开始时记录本次使用的剪刀和触发的玩家
    @Inject(method = "mobInteract", at = @At("HEAD"))
    private void unsuspiciousblock$captureShears(Player player, InteractionHand hand,
                                                 CallbackInfoReturnable<InteractionResult> cir) {
        Sheep sheep = (Sheep) (Object) this;
        ItemStack itemInHand = player.getItemInHand(hand);
        if (sheep.readyForShearing() && itemInHand.is(Items.SHEARS)) {
            this.unsuspiciousblock$shears = itemInHand;
            this.unsuspiciousblock$interactPlayer = player;
        } else {
            this.unsuspiciousblock$shears = ItemStack.EMPTY;
            this.unsuspiciousblock$interactPlayer = null;
        }
    }

    // 在原版完成剪毛后通过 framework 触发额外线掉落
    @Inject(method = "shear", at = @At("TAIL"))
    private void unsuspiciousblock$dropExtraString(SoundSource soundSource, CallbackInfo ci) {
        Sheep sheep = (Sheep) (Object) this;
        if (sheep.level() instanceof ServerLevel serverLevel
                && !this.unsuspiciousblock$shears.isEmpty()
                && this.unsuspiciousblock$interactPlayer instanceof ServerPlayer sp) {
            TriggerContext ctx = TriggerContext.builder(sp, serverLevel)
                    .targetEntity(sheep)
                    .tool(this.unsuspiciousblock$shears)
                    .build();
            EnchantmentManager.dispatch(TriggerType.ENTITY_SHEAR, ctx);
        }
    }

    // 在交互结束后清理本次缓存的剪刀引用和玩家引用
    @Inject(method = "mobInteract", at = @At("RETURN"))
    private void unsuspiciousblock$clearShears(Player player, InteractionHand hand,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        this.unsuspiciousblock$shears = ItemStack.EMPTY;
        this.unsuspiciousblock$interactPlayer = null;
    }
}