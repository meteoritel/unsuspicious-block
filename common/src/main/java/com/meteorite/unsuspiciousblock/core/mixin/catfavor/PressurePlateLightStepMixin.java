package com.meteorite.unsuspiciousblock.core.mixin.catfavor;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.cat.CatPassiveAbilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/** 在原版普通及测重压力板的实体计数入口应用轻步。 */
@Mixin(BasePressurePlateBlock.class)
public abstract class PressurePlateLightStepMixin {

    @WrapOperation(method = "getEntityCount", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static <T extends Entity> List<T> unsuspiciousblock$filterLightStep(Level level, Class<T> entityClass,
            AABB bounds, Predicate<? super T> triggers, Operation<List<T>> original) {
        Predicate<T> eligible = entity -> triggers.test(entity) && (!(entity instanceof Player player)
                || !CatPassiveAbilities.hasLightStepPressurePlateIgnored(player));
        return original.call(level, entityClass, bounds, eligible);
    }
}
