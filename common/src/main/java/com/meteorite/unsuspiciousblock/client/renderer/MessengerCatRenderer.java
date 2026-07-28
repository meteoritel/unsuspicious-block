package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.client.renderer.layer.MessengerCatCollarLayer;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.CatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import com.mojang.math.Axis;
import org.jetbrains.annotations.NotNull;

/**
 * 幽灵猫渲染器 —— 复用原版 CatModel 与纹理，通过半透明发光顶点色营造灵体感
 */
public class MessengerCatRenderer extends MobRenderer<MessengerCat, CatModel<MessengerCat>> {

    // 灵体色调：淡蓝青色（RGB 分量），alpha 由实体阶段动态决定
    private static final int GHOST_R = 170;
    private static final int GHOST_G = 221;
    private static final int GHOST_B = 255;
    // 满阶段 alpha（约 0.6）
    private static final int GHOST_ALPHA_BASE = 153;
    private static final float HOVER_SPEED = 0.15F;
    private static final float HOVER_AMPLITUDE = 0.08F;

    public MessengerCatRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CatModel<>(ctx.bakeLayer(ModelLayers.CAT)), 0.4F);
        // 保留原版项圈层，同样以灵体色调渲染
        this.addLayer(new MessengerCatCollarLayer(this, ctx.getModelSet()));
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(MessengerCat entity) {
        return entity.getTextureId();
    }

    @Override
    protected RenderType getRenderType(@NotNull MessengerCat entity, boolean bodyVisible, boolean translucent, boolean outline) {
        // 始终使用半透明自发光渲染类型，忽略原版 cutout
        return RenderType.entityTranslucentEmissive(this.getTextureLocation(entity));
    }

    @Override
    protected void scale(@NotNull MessengerCat entity, @NotNull PoseStack poseStack, float partialTick) {
        super.scale(entity, poseStack, partialTick);
        // 保持与原版 CatRenderer 相同的体型缩放
        poseStack.scale(0.8F, 0.8F, 0.8F);
    }

    @Override
    protected void setupRotations(@NotNull MessengerCat entity, @NotNull PoseStack poseStack, float bob,
                                  float yBodyRot, float partialTick, float scale) {
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
        // 复刻原版猫躺下时的侧身姿态
        float lieDown = entity.getLieDownAmount(partialTick);
        if (lieDown > 0.0F) {
            poseStack.translate(0.4F * lieDown, 0.15F * lieDown, 0.1F * lieDown);
            poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.rotLerp(lieDown, 0.0F, 90.0F)));
            BlockPos pos = entity.blockPosition();
            for (Player player : entity.level().getEntitiesOfClass(Player.class, new AABB(pos).inflate(2.0, 2.0, 2.0))) {
                if (player.isSleeping()) {
                    poseStack.translate(0.15F * lieDown, 0.0F, 0.0F);
                    break;
                }
            }
        }
    }

    @Override
    public void render(@NotNull MessengerCat entity, float entityYaw, float partialTicks,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        // 灵体上下轻飘
        float hover = Mth.sin((entity.tickCount + partialTicks) * HOVER_SPEED) * HOVER_AMPLITUDE;
        poseStack.translate(0.0F, hover, 0.0F);

        // 显现/消散阶段 alpha 渐入渐出，由实体阶段机驱动
        float alphaProgress = entity.getAlphaProgress(partialTicks);
        int tint = FastColor.ARGB32.color(
                (int) (GHOST_ALPHA_BASE * alphaProgress), GHOST_R, GHOST_G, GHOST_B);
        MultiBufferSource tintedSource = new GhostlyBufferSource(bufferSource, tint);
        super.render(entity, entityYaw, partialTicks, poseStack, tintedSource, 15728880);
        poseStack.popPose();
    }

    @Override
    protected float getShadowRadius(@NotNull MessengerCat entity) {
        // 幽灵不投射实体阴影
        return 0.0F;
    }

    @Override
    protected boolean shouldShowName(@NotNull MessengerCat entity) {
        // 隐藏名称，保持灵体纯粹视觉表现
        return false;
    }

    /**
     * 包装 MultiBufferSource，使所有渲染走灵体色调顶点
     */
    private record GhostlyBufferSource(MultiBufferSource source, int tint) implements MultiBufferSource {
        @Override
        public @NotNull VertexConsumer getBuffer(@NotNull RenderType renderType) {
            return new GhostlyVertexConsumer(this.source.getBuffer(renderType), this.tint);
        }
    }

    /**
         * 包装 VertexConsumer，将顶点颜色与灵体色调相乘，实现半透明与色调统一
         */
        private record GhostlyVertexConsumer(VertexConsumer delegate, int tint) implements VertexConsumer {

        @Override
            public @NotNull VertexConsumer addVertex(float x, float y, float z) {
                return this.delegate.addVertex(x, y, z);
            }

            @Override
            public @NotNull VertexConsumer setColor(int r, int g, int b, int a) {
                int ar = FastColor.ARGB32.red(this.tint);
                int ag = FastColor.ARGB32.green(this.tint);
                int ab = FastColor.ARGB32.blue(this.tint);
                int aa = FastColor.ARGB32.alpha(this.tint);
                return this.delegate.setColor(
                        r * ar / 255,
                        g * ag / 255,
                        b * ab / 255,
                        a * aa / 255
                );
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
