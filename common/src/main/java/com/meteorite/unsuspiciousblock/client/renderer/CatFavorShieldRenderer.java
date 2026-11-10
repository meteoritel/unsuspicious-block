package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.core.effect.ModEffects;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 猫之恩惠保护罩渲染--当本地玩家拥有「猫之恩惠」buff 时，在玩家周围
 * 绘制半透明金色 UV 球体，带轻微呼吸闪烁。仅客户端渲染入口调用。
 *
 * <p>使用 {@link ModRenderTypes} 的保护罩类型：
 * 半透明混合 + 双面渲染 + 深度写入禁用（球体不遮挡其后物体），深度测试保持启用
 * （方块仍可遮挡球体）。
 */
public final class CatFavorShieldRenderer {

    // 保护罩颜色：暖金色（与猫之恩惠 buff 主题一致）
    private static final int COLOR_R = 255;
    private static final int COLOR_G = 217;
    private static final int COLOR_B = 128;
    // 基础透明度与呼吸幅度
    private static final float BASE_ALPHA = 0.22F;
    private static final float BREATH_AMPLITUDE = 0.04F;

    // 球体半径（方块）：略大于玩家碰撞箱，第一人称下仅边缘可见
    private static final float SHIELD_RADIUS = 1.3F;
    // 球体细分：经线（纵向分割）× 纬线（横向分割）
    private static final int LON_SEGMENTS = 24;
    private static final int LAT_SEGMENTS = 16;

    // 球面顶点（坐标已乘半径，每面两个三角形，共 LON×LAT×6 个顶点 × 3 分量）。
    // 几何完全静态：静态初始化一次，每帧只写顶点与 alpha，不再做每周期的 sin/cos 与坐标计算。
    private static final float[] SPHERE_VERTICES = buildSphereVertices();

    private CatFavorShieldRenderer() {
    }

    // 世界渲染阶段入口：仅当本地玩家拥有猫之恩惠 buff 时绘制保护罩
    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (ModEffects.CAT_FAVOR == null || !player.hasEffect(ModEffects.CAT_FAVOR)) {
            return;
        }

        Vec3 camPos = camera.getPosition();
        Vec3 playerPos = player.position();

        poseStack.pushPose();
        // 定位到玩家身体中部（脚部 +0.9）
        poseStack.translate(
                playerPos.x - camPos.x,
                playerPos.y - camPos.y + 0.9,
                playerPos.z - camPos.z
        );

        // 呼吸闪烁：alpha 随 tickCount 正弦波动
        float breath = BREATH_AMPLITUDE * Mth.sin(player.tickCount * 0.1F);
        int alpha = Mth.clamp((int) ((BASE_ALPHA + breath) * 255), 0, 255);

        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(ModRenderTypes.catFavorShield());
        renderSphere(consumer, poseStack.last(), alpha);
        // 立即刷出，避免被后续批次的深度状态干扰
        mc.renderBuffers().bufferSource().endBatch(ModRenderTypes.catFavorShield());

        poseStack.popPose();
    }

    // 静态构建球面顶点序列：每面拆为两个三角形，经线×纬线网格
    private static float[] buildSphereVertices() {
        float[] vertices = new float[LON_SEGMENTS * LAT_SEGMENTS * 6 * 3];
        int index = 0;
        for (int lat = 0; lat < LAT_SEGMENTS; lat++) {
            float phi0 = (float) (lat * Math.PI / LAT_SEGMENTS - Math.PI / 2);
            float phi1 = (float) ((lat + 1) * Math.PI / LAT_SEGMENTS - Math.PI / 2);
            float cosPhi0 = Mth.cos(phi0), sinPhi0 = Mth.sin(phi0);
            float cosPhi1 = Mth.cos(phi1), sinPhi1 = Mth.sin(phi1);

            for (int lon = 0; lon < LON_SEGMENTS; lon++) {
                float theta0 = (float) (lon * 2 * Math.PI / LON_SEGMENTS);
                float theta1 = (float) ((lon + 1) * 2 * Math.PI / LON_SEGMENTS);
                float cosT0 = Mth.cos(theta0), sinT0 = Mth.sin(theta0);
                float cosT1 = Mth.cos(theta1), sinT1 = Mth.sin(theta1);

                // 四角顶点：a(上左) b(上右) c(下右) d(下左)
                float ax = SHIELD_RADIUS * cosPhi0 * cosT0, ay = SHIELD_RADIUS * sinPhi0, az = SHIELD_RADIUS * cosPhi0 * sinT0;
                float bx = SHIELD_RADIUS * cosPhi0 * cosT1, by = SHIELD_RADIUS * sinPhi0, bz = SHIELD_RADIUS * cosPhi0 * sinT1;
                float cx = SHIELD_RADIUS * cosPhi1 * cosT1, cy = SHIELD_RADIUS * sinPhi1, cz = SHIELD_RADIUS * cosPhi1 * sinT1;
                float dx = SHIELD_RADIUS * cosPhi1 * cosT0, dy = SHIELD_RADIUS * sinPhi1, dz = SHIELD_RADIUS * cosPhi1 * sinT0;

                // 三角形 1: a -> b -> c
                index = writeVertex(vertices, index, ax, ay, az);
                index = writeVertex(vertices, index, bx, by, bz);
                index = writeVertex(vertices, index, cx, cy, cz);
                // 三角形 2: a -> c -> d
                index = writeVertex(vertices, index, ax, ay, az);
                index = writeVertex(vertices, index, cx, cy, cz);
                index = writeVertex(vertices, index, dx, dy, dz);
            }
        }
        return vertices;
    }

    private static int writeVertex(float[] vertices, int index, float x, float y, float z) {
        vertices[index] = x;
        vertices[index + 1] = y;
        vertices[index + 2] = z;
        return index + 3;
    }

    // 绘制预设球面：每帧只有 alpha 不同
    private static void renderSphere(VertexConsumer consumer, PoseStack.Pose pose, int alpha) {
        for (int i = 0; i < SPHERE_VERTICES.length; i += 3) {
            consumer.addVertex(pose, SPHERE_VERTICES[i], SPHERE_VERTICES[i + 1], SPHERE_VERTICES[i + 2])
                    .setColor(COLOR_R, COLOR_G, COLOR_B, alpha);
        }
    }
}
