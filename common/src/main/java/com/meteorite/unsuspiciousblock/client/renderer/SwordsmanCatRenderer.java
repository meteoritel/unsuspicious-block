package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.SwordsmanCat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * 剑士猫猫占位渲染器——暂用原版猫模型，并以高亮青白色表现猫国灵体。
 */
public class SwordsmanCatRenderer extends MobRenderer<SwordsmanCat, CatModel<SwordsmanCat>> {
    private static final int SPIRIT_TINT = FastColor.ARGB32.color(190, 220, 245, 255);

    public SwordsmanCatRenderer(EntityRendererProvider.Context context) {
        super(context, new CatModel<>(context.bakeLayer(ModelLayers.CAT)), 0.0F);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(SwordsmanCat entity) {
        return entity.getTextureId();
    }

    @Override
    protected RenderType getRenderType(@NotNull SwordsmanCat entity, boolean bodyVisible,
                                       boolean translucent, boolean outline) {
        return RenderType.entityTranslucentEmissive(this.getTextureLocation(entity));
    }

    @Override
    protected void scale(@NotNull SwordsmanCat entity, @NotNull PoseStack poseStack, float partialTick) {
        poseStack.scale(0.8F, 0.8F, 0.8F);
    }

    @Override
    public void render(@NotNull SwordsmanCat entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource,
                       int packedLight) {
        poseStack.pushPose();
        float hover = Mth.sin((entity.tickCount + partialTick) * 0.18F) * 0.08F;
        poseStack.translate(0.0F, hover, 0.0F);
        int alpha = (int) (FastColor.ARGB32.alpha(SPIRIT_TINT)
                * entity.getRenderAlphaProgress(partialTick));
        int tint = FastColor.ARGB32.color(
                alpha,
                FastColor.ARGB32.red(SPIRIT_TINT),
                FastColor.ARGB32.green(SPIRIT_TINT),
                FastColor.ARGB32.blue(SPIRIT_TINT));
        super.render(entity, entityYaw, partialTick, poseStack,
                new TintedBufferSource(bufferSource, tint), 15728880);
        poseStack.popPose();
    }

    @Override
    protected float getShadowRadius(@NotNull SwordsmanCat entity) {
        return 0.0F;
    }

    private record TintedBufferSource(MultiBufferSource source, int tint) implements MultiBufferSource {
        @Override
        public @NotNull VertexConsumer getBuffer(@NotNull RenderType renderType) {
            return new TintedVertexConsumer(this.source.getBuffer(renderType), this.tint);
        }
    }

    private record TintedVertexConsumer(VertexConsumer delegate, int tint) implements VertexConsumer {
        @Override
        public @NotNull VertexConsumer addVertex(float x, float y, float z) {
            return this.delegate.addVertex(x, y, z);
        }

        @Override
        public @NotNull VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this.delegate.setColor(
                    red * FastColor.ARGB32.red(this.tint) / 255,
                    green * FastColor.ARGB32.green(this.tint) / 255,
                    blue * FastColor.ARGB32.blue(this.tint) / 255,
                    alpha * FastColor.ARGB32.alpha(this.tint) / 255);
        }

        @Override
        public @NotNull VertexConsumer setUv(float u, float v) {
            return this.delegate.setUv(u, v);
        }

        @Override
        public @NotNull VertexConsumer setUv1(int u, int v) {
            return this.delegate.setUv1(u, v);
        }

        @Override
        public @NotNull VertexConsumer setUv2(int u, int v) {
            return this.delegate.setUv2(u, v);
        }

        @Override
        public @NotNull VertexConsumer setNormal(float x, float y, float z) {
            return this.delegate.setNormal(x, y, z);
        }
    }
}
