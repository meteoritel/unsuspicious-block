package com.meteorite.unsuspiciousblock.client.ui.tooltip;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 标本箱 tooltip 客户端渲染器，绘制 5 格缩略 GUI 与内部物品。
 */
public final class ClientSpecimenBoxTooltip implements ClientTooltipComponent {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/specimen_box_preview.png");
    private static final int WIDTH = 96;
    private static final int HEIGHT = 24;
    private static final int SLOT_X = 4;
    private static final int SLOT_Y = 4;
    private static final int SLOT_STEP = 18;

    private final SpecimenBoxTooltip tooltip;

    public ClientSpecimenBoxTooltip(SpecimenBoxTooltip tooltip) {
        this.tooltip = tooltip;
    }

    @Override
    public int getHeight() {
        return HEIGHT + 2;
    }

    @Override
    public int getWidth(@NotNull Font font) {
        return WIDTH;
    }

    @Override
    public void renderImage(@NotNull Font font, int x, int y, GuiGraphics guiGraphics) {
        guiGraphics.blit(TEXTURE, x, y, 0.0F, 0.0F,
                WIDTH, HEIGHT, WIDTH, HEIGHT);
        for (int slot = 0; slot < this.tooltip.items().size(); slot++) {
            ItemStack stack = this.tooltip.items().get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int itemX = x + SLOT_X + slot * SLOT_STEP;
            int itemY = y + SLOT_Y;
            guiGraphics.renderItem(stack, itemX, itemY);
            guiGraphics.renderItemDecorations(font, stack, itemX, itemY);
        }
    }
}
