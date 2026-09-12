package com.meteorite.unsuspiciousblock.mixin.client;

import com.meteorite.unsuspiciousblock.client.pan.PanningAnimation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*** 第三人称淘洗姿势入口，具体手臂动作由客户端动画类处理。 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidPanningMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void unsuspiciousblock$posePan(LivingEntity entity, float limbSwing, float limbSwingAmount,
            float ageInTicks, float headYaw, float headPitch, CallbackInfo ci) {
        PanningAnimation.poseThirdPerson((HumanoidModel<?>)(Object)this, entity, ageInTicks);
    }
}
