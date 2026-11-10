package com.meteorite.unsuspiciousblock.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;

/** 模组自定义 RenderType 的唯一 GL 状态 owner。 */
final class ModRenderTypes {
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
                RenderSystem.depthMask(false);
                RenderSystem.lineWidth(3.0F);
            },
            () -> {
                RenderType.lines().clearRenderState();
                RenderSystem.depthMask(true);
            }) {
    };

    private static final RenderType CAT_FAVOR_SHIELD = new RenderType(
            "unsuspicious_cat_favor_shield",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            256,
            false,
            true,
            () -> {
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);
            },
            () -> {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
            }) {
    };

    private static final RenderType SHIMMER_SURFACE = new RenderType(
            "unsuspicious_shimmer_surface",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            4096,
            false,
            true,
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
            }) {
    };

    private ModRenderTypes() {
    }

    static RenderType noDepthLines() {
        return NO_DEPTH_LINES;
    }

    static RenderType catFavorShield() {
        return CAT_FAVOR_SHIELD;
    }

    static RenderType shimmerSurface() {
        return SHIMMER_SURFACE;
    }
}
