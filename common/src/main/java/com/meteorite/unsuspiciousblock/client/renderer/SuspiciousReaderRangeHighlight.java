package com.meteorite.unsuspiciousblock.client.renderer;

import com.meteorite.unsuspiciousblock.client.state.ReaderScanHighlightState;
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

import java.util.List;

/** 可疑解析仪——范围扫描框预览与扫描结果红色描边的透视渲染（仅客户端） */
public final class SuspiciousReaderRangeHighlight {
    // 范围框：与解析仪主题色一致的金色
    private static final float RANGE_R = 1.0F;
    private static final float RANGE_G = 0.85F;
    private static final float RANGE_B = 0.25F;
    private static final float RANGE_A = 0.6F;

    // 可疑方块描边：警示红色
    private static final float SCAN_R = 1.0F;
    private static final float SCAN_G = 0.15F;
    private static final float SCAN_B = 0.15F;
    private static final float SCAN_A = 0.7F;

    // 战利品容器描边：神秘紫色（与可疑方块红色、范围框金色均区分明显）
    private static final float LOOT_R = 0.7F;
    private static final float LOOT_G = 0.3F;
    private static final float LOOT_B = 1.0F;
    private static final float LOOT_A = 0.7F;

    // 自定义无深度测试的线条 RenderType：用于扫描结果红框透视（穿透墙壁可见）。
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
            },
            RenderType.lines()::clearRenderState
    ) {
    };

    private SuspiciousReaderRangeHighlight() {
    }

    // 在世界渲染阶段绘制范围扫描立方体边框（按住 Shift 时，常规深度测试）与扫描结果红色描边（透视，穿透墙壁可见）
    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) return;

        Vec3 camPos = camera.getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // 1. 范围框预览：仅按住 Shift 且手持范围模式扫描仪、准星对准方块时显示（无需透视，使用默认 lines()）
        if (player.isShiftKeyDown()) {
            VertexConsumer linesConsumer = bufferSource.getBuffer(RenderType.lines());
            if (renderRangeBox(poseStack, linesConsumer, player, mc, camPos)) {
                bufferSource.endBatch(RenderType.lines());
            }
        }

        // 2. 扫描结果红色描边：使用无深度测试 RenderType 实现透视（闪烁阶段可能返回空）
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

    // 绘制扫描到的高亮方块描边：可疑方块红色、战利品容器紫色，返回是否实际绘制
    private static boolean renderScanResults(PoseStack poseStack, VertexConsumer consumer, Vec3 camPos) {
        boolean rendered = false;
        // 可疑方块：红色描边
        for (BlockPos pos : ReaderScanHighlightState.getRenderSuspiciousBlocks()) {
            LevelRenderer.renderLineBox(poseStack, consumer,
                    pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z,
                    pos.getX() + 1 - camPos.x, pos.getY() + 1 - camPos.y, pos.getZ() + 1 - camPos.z,
                    SCAN_R, SCAN_G, SCAN_B, SCAN_A);
            rendered = true;
        }
        // 战利品容器：紫色描边（仅高亮，不解析）
        for (BlockPos pos : ReaderScanHighlightState.getRenderLootContainers()) {
            LevelRenderer.renderLineBox(poseStack, consumer,
                    pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z,
                    pos.getX() + 1 - camPos.x, pos.getY() + 1 - camPos.y, pos.getZ() + 1 - camPos.z,
                    LOOT_R, LOOT_G, LOOT_B, LOOT_A);
            rendered = true;
        }
        return rendered;
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
