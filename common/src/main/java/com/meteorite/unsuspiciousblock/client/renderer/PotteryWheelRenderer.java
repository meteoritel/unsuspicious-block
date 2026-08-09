package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.block.PotteryWheelBlock;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/** 渲染陶轮台转盘、随转盘旋转的湿黏土及工作动画。 */
public final class PotteryWheelRenderer implements BlockEntityRenderer<PotteryWheelBlockEntity> {
    private static final ResourceLocation TURNTABLE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/block/pottery_wheel_turntable.png");
    private static final ResourceLocation CLAY_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/block/pottery_wheel_clay.png");
    private static final float ROTATION_DEGREES_PER_TICK = 12.0F;
    private static final float TURNTABLE_TOP_Y = 14.0F / 16.0F;

    private final ModelPart turntable;
    private final ModelPart clay;

    public PotteryWheelRenderer(BlockEntityRendererProvider.Context context) {
        this.turntable = PotteryWheelModel.turntable(context.bakeLayer(PotteryWheelModel.TURNTABLE_LAYER));
        this.clay = PotteryWheelModel.clay(context.bakeLayer(PotteryWheelModel.CLAY_LAYER));
    }

    @Override
    public void render(@NotNull PotteryWheelBlockEntity wheel, float partialTick, @NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState state = wheel.getBlockState();
        boolean working = state.getValue(PotteryWheelBlock.WORKING);

        poseStack.pushPose();
        poseStack.translate(0.5F, TURNTABLE_TOP_Y, 0.5F);
        if (working) {
            Level level = wheel.getLevel();
            long gameTime = level == null ? 0L : level.getGameTime();
            poseStack.mulPose(Axis.YP.rotationDegrees(
                    (gameTime + partialTick) * ROTATION_DEGREES_PER_TICK));
        }
        VertexConsumer turntableBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TURNTABLE_TEXTURE));
        turntable.render(poseStack, turntableBuffer, packedLight, OverlayTexture.NO_OVERLAY);
        if (state.getValue(PotteryWheelBlock.WET_CLAY)) {
            VertexConsumer clayBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CLAY_TEXTURE));
            clay.render(poseStack, clayBuffer, packedLight, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }
}
