package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.meteosuikit.api.render.UiIcon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * 标本箱界面--暂用漏斗 GUI 贴图，5 格容器 + 玩家物品栏标准布局。
 */
public class SpecimenBoxScreen extends AbstractContainerScreen<SpecimenBoxMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/container/hopper.png");

    public SpecimenBoxScreen(SpecimenBoxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 133;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        com.meteorite.meteosuikit.api.render.UiFlatText.drawWrapped(graphics, font, title,
                titleLabelX, titleLabelY, imageWidth - titleLabelX - 6, 0x404040);
        com.meteorite.meteosuikit.api.render.UiFlatText.drawWrapped(graphics, font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, imageWidth - inventoryLabelX - 6, 0x404040);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        new UiIcon.Sprite(TEXTURE, 0, 0, imageWidth, imageHeight, 256, 256).render(guiGraphics, leftPos, topPos);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
