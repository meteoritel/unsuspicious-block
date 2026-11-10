package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.core.entity.SpiritCat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * 统一灵体猫的色罩、悬浮、满光和无阴影表现。
 */
public abstract class SpiritCatRenderer<T extends SpiritCat> extends MobRenderer<T, CatModel<T>> {
    protected static final int FULL_BRIGHT = 15728880;
    private static final float DEFAULT_HOVER_SPEED = 0.15F;
    private static final float HOVER_AMPLITUDE = 0.08F;

    protected SpiritCatRenderer(EntityRendererProvider.Context context, CatModel<T> model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull T entity) {
        return entity.getTextureId();
    }

    @Override
    protected RenderType getRenderType(@NotNull T entity, boolean bodyVisible,
                                       boolean translucent, boolean outline) {
        return spiritRenderType(entity);
    }

    protected abstract RenderType spiritRenderType(T entity);

    protected abstract int spiritTint();

    /**
     * 剑士猫使用独立悬浮频率，其他灵体沿用默认值。
     */
    protected float hoverSpeed() {
        return DEFAULT_HOVER_SPEED;
    }

    protected float modelScale() {
        return 1.0F;
    }

    @Override
    protected void scale(@NotNull T entity, @NotNull PoseStack poseStack, float partialTick) {
        super.scale(entity, poseStack, partialTick);
        float scale = modelScale();
        poseStack.scale(scale, scale, scale);
    }

    @Override
    public void render(@NotNull T entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource,
                       int packedLight) {
        poseStack.pushPose();
        float hover = Mth.sin((entity.tickCount + partialTick) * hoverSpeed()) * HOVER_AMPLITUDE;
        poseStack.translate(0.0F, hover, 0.0F);
        int baseTint = spiritTint();
        int alpha = (int) (FastColor.ARGB32.alpha(baseTint)
                * entity.getRenderAlphaProgress(partialTick));
        int tint = FastColor.ARGB32.color(alpha,
                FastColor.ARGB32.red(baseTint),
                FastColor.ARGB32.green(baseTint),
                FastColor.ARGB32.blue(baseTint));
        super.render(entity, entityYaw, partialTick, poseStack,
                new TintedBufferSource(bufferSource, tint), FULL_BRIGHT);
        poseStack.popPose();
    }

    @Override
    protected float getShadowRadius(@NotNull T entity) {
        return 0.0F;
    }

    private record TintedBufferSource(MultiBufferSource source, int tint) implements MultiBufferSource {
        @Override
        public @NotNull VertexConsumer getBuffer(@NotNull RenderType renderType) {
            return new TintedVertexConsumer(source.getBuffer(renderType), tint);
        }
    }

    private record TintedVertexConsumer(VertexConsumer delegate, int tint) implements VertexConsumer {
        @Override
        public @NotNull VertexConsumer addVertex(float x, float y, float z) {
            return delegate.addVertex(x, y, z);
        }

        @Override
        public @NotNull VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return delegate.setColor(
                    red * FastColor.ARGB32.red(tint) / 255,
                    green * FastColor.ARGB32.green(tint) / 255,
                    blue * FastColor.ARGB32.blue(tint) / 255,
                    alpha * FastColor.ARGB32.alpha(tint) / 255);
        }

        @Override
        public @NotNull VertexConsumer setUv(float u, float v) {
            return delegate.setUv(u, v);
        }

        @Override
        public @NotNull VertexConsumer setUv1(int u, int v) {
            return delegate.setUv1(u, v);
        }

        @Override
        public @NotNull VertexConsumer setUv2(int u, int v) {
            return delegate.setUv2(u, v);
        }

        @Override
        public @NotNull VertexConsumer setNormal(float x, float y, float z) {
            return delegate.setNormal(x, y, z);
        }
    }
}
