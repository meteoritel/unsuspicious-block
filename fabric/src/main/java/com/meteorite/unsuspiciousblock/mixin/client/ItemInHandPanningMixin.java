package com.meteorite.unsuspiciousblock.mixin.client;

import com.meteorite.unsuspiciousblock.client.pan.PanningAnimation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*** Fabric 第一人称淘盘动画入口，补齐 NeoForge RenderHandEvent 的接入能力。 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandPanningMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$renderPan(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
            PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (PanningAnimation.renderFirstPerson((ItemInHandRenderer)(Object)this, player, hand, stack,
                partialTick, equipProgress, pose, buffers, light)) {
            ci.cancel();
        }
    }
}
