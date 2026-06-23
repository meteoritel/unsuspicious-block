package com.meteorite.unsuspiciousblock.mixin.interaction;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric 平台绵羊剪毛触发入口——HEAD 注入 {@link Sheep#mobInteract}，
 * 在原版剪毛逻辑执行前 dispatch {@link TriggerType#ENTITY_SHEAR}。
 * <p>
 * 仅 Fabric 需要：NeoForge 的 ShearsItem.interactLivingEntity 经 IShearable patch
 * 绕过 Sheep.mobInteract，故 NeoForge 端改用 PlayerInteractEvent.EntityInteract。
 */
@Mixin(Sheep.class)
public abstract class SheepMixin {

    @Inject(method = "mobInteract", at = @At("HEAD"))
    private void unsuspiciousblock$onSheepShear(Player player, InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        Sheep sheep = (Sheep) (Object) this;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.SHEARS) || !sheep.readyForShearing()) {
            return;
        }
        if (!(player instanceof ServerPlayer sp) || !(sheep.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        TriggerContext ctx = TriggerContext.builder(sp, serverLevel)
                .targetEntity(sheep)
                .tool(stack)
                .build();
        EnchantmentManager.dispatch(TriggerType.ENTITY_SHEAR, ctx);
    }
}
