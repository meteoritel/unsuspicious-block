package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/*** 贴水波光：以错峰明灭的金白色细线表现水中反光，在半透明水体之后绘制。 */
public final class ShimmerSurfaceRenderer {
    private static final double VIEW_DISTANCE = 32.0D;
    // 仅写颜色、保留深度测试；波光不能穿墙，也不覆盖后续粒子的深度。
    private static final RenderType SURFACE_TYPE = new RenderType(
            "unsuspicious_shimmer_surface", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 4096, false, true,
            () -> {
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                RenderSystem.enableDepthTest();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);
            },
            () -> {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
            }) {};

    private ShimmerSurfaceRenderer() {
    }

    // 两端世界渲染事件入口；使用相机坐标，兼容第三人称与旁观视角。
    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Vec3 eye = camera.getPosition();
        AABB area = new AABB(eye, eye).inflate(VIEW_DISTANCE);
        var shimmers = client.level.getEntitiesOfClass(ShimmerEntity.class, area,
                shimmer -> !shimmer.isRemoved() && shimmer.getPanRemaining() > 0);
        if (shimmers.isEmpty()) {
            return;
        }
        float partialTick = client.getTimer().getGameTimeDeltaPartialTick(false);
        VertexConsumer vertices = client.renderBuffers().bufferSource().getBuffer(SURFACE_TYPE);
        for (ShimmerEntity shimmer : shimmers) {
            double distance = shimmer.position().distanceTo(eye);
            if (distance >= VIEW_DISTANCE) {
                continue;
            }
            BlockPos pos = shimmer.blockPosition();
            FluidState fluid = client.level.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)) {
                continue;
            }
            float fade = Mth.clamp((float)((VIEW_DISTANCE - distance) / 8.0D), 0.0F, 1.0F);
            poseStack.pushPose();
            try {
                // 实际流体表面高度上抬少量，避免波光埋在水面内或与水面闪烁冲突。
                poseStack.translate(shimmer.getX() - eye.x,
                        pos.getY() + fluid.getHeight(client.level, pos) + 0.006D - eye.y,
                        shimmer.getZ() - eye.z);
                drawGlints(vertices, poseStack.last(), shimmer, partialTick, fade);
            } finally {
                poseStack.popPose();
            }
        }
        client.renderBuffers().bufferSource().endBatch(SURFACE_TYPE);
    }

    // 固定数量与确定性分布，不在逐帧渲染中建立随机数或粒子对象。
    private static void drawGlints(VertexConsumer vertices, PoseStack.Pose pose,
            ShimmerEntity shimmer, float partialTick, float distanceFade) {
        float time = shimmer.tickCount + partialTick;
        float seed = (shimmer.getId() & 255) * 0.73F;
        // 三档通过数量区分，最后一次也保持足够亮度，不会被误认为已耗尽。
        int count = shimmer.getPanRemaining() >= 3 ? 48 : (shimmer.getPanRemaining() == 2 ? 28 : 12);
        for (int i = 0; i < count; i++) {
            float angle = i * 2.399963F + seed;
            float radius = 0.43F * Mth.sqrt((i + 0.5F) / count);
            float pulse = Math.max(0.0F, Mth.sin(time * (0.075F + (i % 4) * 0.012F) + i * 1.71F + seed));
            int alpha = (int)((85.0F + 170.0F * pulse * pulse) * distanceFade);
            if (alpha < 8) {
                continue;
            }
            float x = Mth.cos(angle) * radius + Mth.sin(time * 0.055F + i) * 0.008F;
            float z = Mth.sin(angle) * radius + Mth.cos(time * 0.045F + i) * 0.012F;
            float width = 0.011F + (i % 3) * 0.002F;
            float length = 0.028F + pulse * 0.032F;
            if (shimmer.isPanning()) {
                z += Mth.sin(time * 0.3F + i) * 0.018F;
            }
            quad(vertices, pose, x, z, width, length, 255, 223, 115, alpha);
            // 亮芯仍是一条短线，不绘制朝向相机的十字星。
            quad(vertices, pose, x, z, width * 0.5F, length * 0.4F, 255, 249, 213, alpha);
        }
    }

    // 在 XZ 水平面绘制细长矩形，所有反光均贴合水面。
    private static void quad(VertexConsumer vertices, PoseStack.Pose pose,
            float x, float z, float halfWidth, float halfLength, int r, int g, int b, int alpha) {
        vertices.addVertex(pose, x - halfWidth, 0.0F, z - halfLength).setColor(r, g, b, alpha);
        vertices.addVertex(pose, x - halfWidth, 0.0F, z + halfLength).setColor(r, g, b, alpha);
        vertices.addVertex(pose, x + halfWidth, 0.0F, z + halfLength).setColor(r, g, b, alpha);
        vertices.addVertex(pose, x + halfWidth, 0.0F, z - halfLength).setColor(r, g, b, alpha);
    }
}
