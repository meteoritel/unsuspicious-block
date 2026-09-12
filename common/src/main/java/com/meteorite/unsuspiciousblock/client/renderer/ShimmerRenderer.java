package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 闪烁的光渲染器——实体本体不渲染任何模型。
 * <p>
 * 贴水波光由 ShimmerSurfaceRenderer 在水体之后绘制，粒子由 ShimmerEntity 客户端 tick 发射，
 * 因此这里保持空实现，只占用渲染器注册位避免客户端缺失渲染器警告。
 * 纹理路径仅作占位，实际不会被绑定。
 */
public class ShimmerRenderer extends EntityRenderer<ShimmerEntity> {
    private static final ResourceLocation PLACEHOLDER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/shimmer.png");

    public ShimmerRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull ShimmerEntity entity) {
        return PLACEHOLDER_TEXTURE;
    }

    @Override
    public void render(@NotNull ShimmerEntity entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight) {
        // 无实体模型：贴水波光与粒子分别由世界渲染阶段和客户端 tick 处理。
    }
}
