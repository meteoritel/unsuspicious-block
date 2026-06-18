package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 柔软肉垫（恩惠≥60）——不触发压力板/绊线钩/绊线。
 * 压力板与绊线均通过 Entity.isIgnoringBlockTriggers 判定是否忽略实体，
 * 故对持有该被动的玩家令该方法返回 true，即可一并屏蔽这些机关。
 */
@Mixin(Entity.class)
public abstract class EntityBlockTriggerMixin {

    @Inject(method = "isIgnoringBlockTriggers", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$softPawsIgnoreTriggers(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Player player && CatPassiveAbilities.hasSoftPaws(player)) {
            cir.setReturnValue(true);
        }
    }
}
