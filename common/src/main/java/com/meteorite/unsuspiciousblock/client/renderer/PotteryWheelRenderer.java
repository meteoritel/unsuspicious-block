package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.block.PotteryWheelBlock;
import com.meteorite.unsuspiciousblock.core.blockentity.PotteryWheelBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/** 渲染陶轮台的转盘、湿黏土和槽内水面。 */
public final class PotteryWheelRenderer implements BlockEntityRenderer<PotteryWheelBlockEntity> {
    private static final ResourceLocation TURNTABLE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/block/pottery_wheel_turntable.png");
    private static final ResourceLocation CLAY_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/block/pottery_wheel_clay.png");
    private static final float ROTATION_DEGREES_PER_TICK = 12.0F;
    private static final float TURNTABLE_TOP_Y = 14.0F / 16.0F;
    // 北向模型中水槽的内壁坐标，水面低于台面。
    private static final float BASIN_MIN_X = 12.0F / 16.0F;
    private static final float BASIN_MAX_X = 14.0F / 16.0F;
    private static final float BASIN_MIN_Z = 2.0F / 16.0F;
    private static final float BASIN_MAX_Z = 4.0F / 16.0F;
    private static final float WATER_SURFACE_Y = 11.625F / 16.0F;
    private static final float WATER_UV_MIN = 0.25F;
    private static final float WATER_UV_MAX = 0.75F;

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
        Level level = wheel.getLevel();
        boolean working = state.getValue(PotteryWheelBlock.WORKING);

        poseStack.pushPose();
        poseStack.translate(0.5F, TURNTABLE_TOP_Y, 0.5F);
        if (working) {
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

        if (state.getValue(PotteryWheelBlock.HAS_WATER) && level != null) {
            renderBasinWater(level, wheel.getBlockPos(), state.getValue(HorizontalDirectionalBlock.FACING),
                    poseStack, bufferSource, packedLight);
        }
    }

    private void renderBasinWater(Level level, BlockPos pos, Direction facing, PoseStack poseStack,
                                 MultiBufferSource buffers, int light) {
        // 从原版水方块模型取得随资源包重载的静水动画精灵。
        TextureAtlasSprite stillWater = Minecraft.getInstance().getModelManager().getBlockModelShaper()
                .getBlockModel(Blocks.WATER.defaultBlockState()).getParticleIcon();
        float uMin = stillWater.getU(WATER_UV_MIN);
        float uMax = stillWater.getU(WATER_UV_MAX);
        float vMin = stillWater.getV(WATER_UV_MIN);
        float vMax = stillWater.getV(WATER_UV_MAX);
        int color = 0xFF000000 | BiomeColors.getAverageWaterColor(level, pos);
        VertexConsumer water = buffers.getBuffer(RenderType.translucentMovingBlock());

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YN.rotationDegrees(facing.getOpposite().toYRot()));
        poseStack.translate(-0.5F, 0.0F, -0.5F);
        PoseStack.Pose pose = poseStack.last();
        waterVertex(water, pose, BASIN_MIN_X, BASIN_MAX_Z, uMin, vMax, color, light);
        waterVertex(water, pose, BASIN_MAX_X, BASIN_MAX_Z, uMax, vMax, color, light);
        waterVertex(water, pose, BASIN_MAX_X, BASIN_MIN_Z, uMax, vMin, color, light);
        waterVertex(water, pose, BASIN_MIN_X, BASIN_MIN_Z, uMin, vMin, color, light);
        poseStack.popPose();
    }

    private void waterVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float z,
                             float u, float v, int color, int light) {
        buffer.addVertex(pose, x, WATER_SURFACE_Y, z)
                .setColor(color)
                .setUv(u, v)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
