package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.item.SuspiciousReaderItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 可疑解析仪——范围扫描框预览与扫描结果青色描边的透视渲染（仅客户端） */
public final class SuspiciousReaderRangeHighlight {
    // 范围框：与解析仪主题色一致的金色
    private static final float RANGE_R = 1.0F;
    private static final float RANGE_G = 0.85F;
    private static final float RANGE_B = 0.25F;
    private static final float RANGE_A = 0.6F;

    // 青色与沙子、砂岩的暖色背景形成对比，保持高不透明度便于寻找地下目标。
    private static final float SCAN_R = 0.1F;
    private static final float SCAN_G = 1.0F;
    private static final float SCAN_B = 1.0F;
    private static final float SCAN_A = 1.0F;

    // 战利品容器描边：紫色，与可疑方块青色、范围框金色区分。
    private static final float LOOT_R = 0.7F;
    private static final float LOOT_G = 0.3F;
    private static final float LOOT_B = 1.0F;
    private static final float LOOT_A = 0.7F;

    // 自定义无深度测试的线条 RenderType：用于扫描结果描边透视（穿透墙壁可见）。
    // 直接调用 RenderSystem.disableDepthTest() 无效——RenderType.lines() 在 endBatch
    // 时会通过 setupRenderState() 重新启用深度测试覆盖手动禁用，故必须用自定义 RenderType。
    // 由于 RenderType.create(...) 为包级私有，这里通过 public 构造器创建匿名子类：
    // setup 阶段复用 lines() 的全部状态（着色器、线宽、混合等），再禁用深度测试。
    private static final RenderType NO_DEPTH_LINES = new RenderType(
            "unsuspicious_no_depth_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256,
            false,
            false,
            () -> {
                RenderType.lines().setupRenderState();
                RenderSystem.disableDepthTest();
                RenderSystem.lineWidth(3.0F);
            },
            RenderType.lines()::clearRenderState
    ) {
    };

    private SuspiciousReaderRangeHighlight() {
    }

    // 在世界渲染阶段绘制范围预览（常规深度测试）与青色扫描结果（透视）。
    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null || mc.options.hideGui) return;

        Vec3 camPos = camera.getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // 1. 范围框预览：仅按住 Shift 且手持范围模式扫描仪、准星对准方块时显示（无需透视，使用默认 lines()）
        if (player.isShiftKeyDown()) {
            VertexConsumer linesConsumer = bufferSource.getBuffer(RenderType.lines());
            if (renderRangeBox(poseStack, linesConsumer, player, mc, camPos)) {
                bufferSource.endBatch(RenderType.lines());
            }
        }

        // 2. 扫描结果青色描边：加粗并透视，整批结果共用淡出。
        VertexConsumer noDepthConsumer = bufferSource.getBuffer(NO_DEPTH_LINES);
        if (renderScanResults(poseStack, noDepthConsumer, camPos)) {
            bufferSource.endBatch(NO_DEPTH_LINES);
        }
    }

    // 绘制范围扫描立方体边框，返回是否实际绘制
    private static boolean renderRangeBox(PoseStack poseStack, VertexConsumer consumer,
                                          LocalPlayer player, Minecraft mc, Vec3 camPos) {
        ItemStack readerStack = getReaderStack(player);
        if (readerStack.isEmpty()) return false;
        int scanLevel = SuspiciousReaderItem.getScanLevel(readerStack);
        if (scanLevel <= 0) return false;

        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos clickedPos = blockHit.getBlockPos();
        Direction faceDir = blockHit.getDirection();
        // 复用服务端的几何计算，保证高亮范围与实际扫描范围一致
        BlockPos cubeCenter = SuspiciousReaderItem.getRangeScanCenter(clickedPos, faceDir, scanLevel);
        int half = scanLevel;
        BlockPos min = cubeCenter.offset(-half, -half, -half);
        BlockPos max = cubeCenter.offset(half, half, half);

        // PoseStack 处于世界原点，需要传入相机相对坐标
        LevelRenderer.renderLineBox(poseStack, consumer,
                min.getX() - camPos.x, min.getY() - camPos.y, min.getZ() - camPos.z,
                max.getX() + 1 - camPos.x, max.getY() + 1 - camPos.y, max.getZ() + 1 - camPos.z,
                RANGE_R, RANGE_G, RANGE_B, RANGE_A);
        return true;
    }

    // 所有目标保持稳定描边；空方块弱化，当前聚焦目标增加外框。
    private static boolean renderScanResults(PoseStack poseStack, VertexConsumer consumer, Vec3 camPos) {
        var snapshot = ReaderScanHudState.snapshot();
        float alpha = snapshot.alpha(ReaderScanHudState.now());
        if (alpha <= 0) return false;
        boolean rendered = false;
        if (snapshot.range()) {
            for (var target : snapshot.blocks()) {
                boolean empty = target.entry().isEmpty();
                drawTarget(poseStack, consumer, camPos, target.pos(), 0,
                        empty ? 0.55F : SCAN_R, empty ? 0.55F : SCAN_G, empty ? 0.55F : SCAN_B,
                        alpha * (empty ? 0.3F : SCAN_A));
                rendered = true;
            }
            for (var target : snapshot.containers()) {
                drawTarget(poseStack, consumer, camPos, target.pos(), 0, LOOT_R, LOOT_G, LOOT_B, alpha * LOOT_A);
                rendered = true;
            }
        }
        var focused = ReaderScanHudState.focusedTarget();
        if (focused != null) {
            drawTarget(poseStack, consumer, camPos, focused.pos(), 0.025, 1.0F, 1.0F, 0.85F, alpha);
            rendered = true;
        }
        return rendered;
    }

    private static void drawTarget(PoseStack poseStack, VertexConsumer consumer, Vec3 camera, BlockPos pos,
                                   double expand, float red, float green, float blue, float alpha) {
        LevelRenderer.renderLineBox(poseStack, consumer,
                pos.getX() - camera.x - expand, pos.getY() - camera.y - expand, pos.getZ() - camera.z - expand,
                pos.getX() + 1 - camera.x + expand, pos.getY() + 1 - camera.y + expand, pos.getZ() + 1 - camera.z + expand,
                red, green, blue, alpha);
    }

    // 从主手/副手获取可疑解析仪
    private static ItemStack getReaderStack(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() == ModItems.SUSPICIOUS_READER) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() == ModItems.SUSPICIOUS_READER) return off;
        return ItemStack.EMPTY;
    }
}
