package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.world.NaturalBoneBlockTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 化石猎手 /place template 调试兼容——在 {@link StructureTemplate#placeInWorld} 完成后，
 * 若由 {@code /place template} 指令触发（level 为 ServerLevel 而非 WorldGenRegion），
 * 则扫描模板 bounding box 内的骨块并标记为自然生成。
 * <p>
 * 正常结构生成路径（WorldGenRegion）由 chunk 首生成扫描覆盖，此处不处理。
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {

    @Inject(method = "placeInWorld", at = @At("RETURN"))
    private void unsuspiciousblock$onPlaceTemplate(ServerLevelAccessor level, BlockPos pos1, BlockPos pos2,
                                                    StructurePlaceSettings settings, RandomSource random, int flags,
                                                    CallbackInfoReturnable<Boolean> cir) {
        // 放置失败则跳过
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        // 仅 /place template 路径（ServerLevel）处理，正常结构生成（WorldGenRegion）由 chunk 扫描覆盖
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        StructureTemplate self = (StructureTemplate) (Object) this;
        BoundingBox box = self.getBoundingBox(settings, pos1);
        NaturalBoneBlockTracker.scanBoundingBox(serverLevel, box);
    }
}
