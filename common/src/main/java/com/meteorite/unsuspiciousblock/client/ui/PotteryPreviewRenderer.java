package com.meteorite.unsuspiciousblock.client.ui;

import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;

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
        dispatcher.renderItem(previewPot, graphics.pose(), graphics.bufferSource(),
                15728880, OverlayTexture.NO_OVERLAY);
        graphics.flush();
        graphics.pose().popPose();
    }
}
