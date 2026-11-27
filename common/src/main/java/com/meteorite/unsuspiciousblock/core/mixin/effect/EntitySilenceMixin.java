package com.meteorite.unsuspiciousblock.core.mixin.effect;

import com.meteorite.unsuspiciousblock.core.effect.PurrmintEffectService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 喵步静音音效入口——在脚步、缓冲脚步与涉水音效播放前交由 {@link PurrmintEffectService} 判定是否静音。
 */
@Mixin(Entity.class)
public abstract class EntitySilenceMixin {

    @Inject(method = "walkingStepSound", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$silenceWalkingStep(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (PurrmintEffectService.shouldSilenceMovement((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "playMuffledStepSound", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$silenceMuffledStep(BlockState state, CallbackInfo ci) {
        if (PurrmintEffectService.shouldSilenceMovement((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "playCombinationStepSounds", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$silenceCombinationStep(BlockState primaryState, BlockState secondaryState,
                                                          CallbackInfo ci) {
        if (PurrmintEffectService.shouldSilenceMovement((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "playSwimSound", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$silenceSwimSound(float volume, CallbackInfo ci) {
        if (PurrmintEffectService.shouldSilenceMovement((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
