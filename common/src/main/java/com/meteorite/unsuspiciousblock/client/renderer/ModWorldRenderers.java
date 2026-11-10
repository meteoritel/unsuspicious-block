package com.meteorite.unsuspiciousblock.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;

import java.util.List;
import java.util.function.BiConsumer;

/** 世界叠加绘制清单；平台保留各自的世界渲染阶段适配。 */
public final class ModWorldRenderers {
    private static final List<BiConsumer<PoseStack, Camera>> MANIFEST = List.of(
            SuspiciousReaderRangeHighlight::render,
            CatFavorShieldRenderer::render,
            ShimmerSurfaceRenderer::render
    );

    private ModWorldRenderers() {
    }

    /** 由平台在半透明方块之后的对应阶段按固定顺序绘制。 */
    public static void renderAll(PoseStack poseStack, Camera camera) {
        for (BiConsumer<PoseStack, Camera> renderer : MANIFEST) {
            renderer.accept(poseStack, camera);
        }
    }
}
