package com.meteorite.unsuspiciousblock.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 运行时模板放置按成功写入的位置登记自然骨块。 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {

    @WrapOperation(method = "placeInWorld", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean unsuspiciousblock$markPlacedBone(ServerLevelAccessor world, BlockPos pos, BlockState state,
                                                    int flags, Operation<Boolean> original) {
        boolean placed = original.call(world, pos, state, flags);
        if (placed && state.is(Blocks.BONE_BLOCK) && world instanceof ServerLevel level) {
            NaturalBoneBlockTracker.markNatural(level, pos);
        }
        return placed;
    }
}
