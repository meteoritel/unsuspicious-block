package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.LanternPet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * 灵魂提灯宠物渲染器 —— 使用自定义 LanternPetModel 渲染精致飞行提灯
 * <p>
 * 保留原有上下浮动与朝向旋转逻辑，贴图改为独立实体贴图。
 * 灯笼自发光 15 级光照，不显示名称牌。
 */
public class LanternPetRenderer extends MobRenderer<LanternPet, LanternPetModel> {

    private static final float BOB_SPEED = 0.15F;
    private static final float BOB_AMPLITUDE = 0.08F;
    private static final float MODEL_SCALE = 0.7F;
    // 提灯悬挂点偏移：让模型底部悬在实体坐标下方
    private static final float VERTICAL_OFFSET = -0.3F;

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock",
                    "textures/entity/soul_lantern_pet.png");

    public LanternPetRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new LanternPetModel(ctx.bakeLayer(LanternPetModel.LAYER_LOCATION)), 0.3F);
        // TODO: 玻璃半透明 RenderLayer（用 entityTranslucent 渲染玻璃面，骨架保持 cutout，营造透明玻璃质感）
        // TODO: 火苗 emissive 发光层（叠加满光照自发光贴图，强化灵魂火苗的发光视觉）
        // TODO: 灵魂粒子拖尾 RenderLayer（移动时在模型后方追加 SOUL 粒子拖尾，与实体粒子联动）
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull LanternPet entity) {
        return TEXTURE;
    }

    @Override
    public void render(@NotNull LanternPet entity, float entityYaw, float partialTicks,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource,
                       int packedLight) {
        poseStack.pushPose();

        // 上下浮动：与实体 aiStep 中施加的速度同相
        float bob = Mth.sin((entity.tickCount + partialTicks) * BOB_SPEED) * BOB_AMPLITUDE;
        poseStack.translate(0.0F, bob + VERTICAL_OFFSET, 0.0F);

        // 朝向：跟随实体 yRot 旋转
        float yRot = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yRot));

        // 缩放：将模型渲染为略小于原版灯笼尺寸
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

        super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    @Override
    protected boolean shouldShowName(@NotNull LanternPet entity) {
        return false;
    }

    @Override
    protected int getBlockLightLevel(@NotNull LanternPet entity, @NotNull BlockPos pos) {
        // 灵魂灯笼自带 15 级光照，让实体本身成为光源
        return 15;
    }
}
