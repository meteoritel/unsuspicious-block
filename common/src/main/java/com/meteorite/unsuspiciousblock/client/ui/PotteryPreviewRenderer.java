package com.meteorite.unsuspiciousblock.client.ui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import org.jetbrains.annotations.NotNull;

/** 在陶轮台 GUI 与配方查看器中复用的动态纹饰陶罐预览渲染器。 */
public final class PotteryPreviewRenderer {
    private final DecoratedPotBlockEntity previewPot = new DecoratedPotBlockEntity(
            BlockPos.ZERO, Blocks.DECORATED_POT.defaultBlockState());
    private ItemStack previewSource = ItemStack.EMPTY;

    // 在指定位置绘制持续自转并保留四面纹饰的原版陶罐
    public void render(GuiGraphics graphics, ItemStack output,
                       float x, float y, float scale, float manualRotation) {
        if (!ItemStack.isSameItemSameComponents(output, previewSource)) {
            previewPot.setFromItem(output);
            previewSource = output.copy();
        }

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 120.0F);
        graphics.pose().scale(scale, -scale, scale);
        graphics.pose().mulPose(Axis.XP.rotationDegrees(18.0F));
        float automaticRotation = (Util.getMillis() % 12000L) * 0.03F;
        graphics.pose().mulPose(Axis.YP.rotationDegrees(automaticRotation + manualRotation));
        graphics.pose().translate(-0.5F, 0.0F, -0.5F);
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        Lighting.setupFor3DItems();
        dispatcher.renderItem(previewPot, graphics.pose(), new EvenlyLitBufferSource(graphics.bufferSource()),
                15728880, OverlayTexture.NO_OVERLAY);
        graphics.flush();
        graphics.pose().popPose();
    }

    /** 为预览模型的所有 RenderType 提供统一亮度的顶点输出。 */
    private record EvenlyLitBufferSource(MultiBufferSource source) implements MultiBufferSource {
        @Override
        public @NotNull VertexConsumer getBuffer(@NotNull RenderType renderType) {
            return new EvenlyLitVertexConsumer(source.getBuffer(renderType));
        }
    }

    /** 将各面的法线统一朝上，消除 GUI 方向光在陶罐侧面产生的阴影。 */
    private record EvenlyLitVertexConsumer(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public @NotNull VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public @NotNull VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public @NotNull VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public @NotNull VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public @NotNull VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public @NotNull VertexConsumer setNormal(float x, float y, float z) {
            // 对齐 1.21.1 GUI 3D 光源的变换方向，避免统一法线落在背光面。
            delegate.setNormal(-0.64F, -0.77F, -0.03F);
            return this;
        }
    }
}
