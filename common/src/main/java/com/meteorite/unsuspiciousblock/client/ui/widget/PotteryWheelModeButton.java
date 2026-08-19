package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/** 纹饰陶轮台工作许可按钮，按顺序循环启用、禁用和红石启用。 */
public final class PotteryWheelModeButton extends IconButton {
    private static final ResourceLocation ICON_ATLAS = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/toolbar_icons.png");
    private static final int BORDER = 0xFF4D4D4D;
    private static final int BG = 0xFFC6C6C6;
    private static final int BG_HOVER = 0xFFE0E0E0;
    private PotteryWheelBlockEntity.ControlMode mode = PotteryWheelBlockEntity.ControlMode.ENABLED;
    private final Consumer<PotteryWheelBlockEntity.ControlMode> onModeChanged;

    public PotteryWheelModeButton(int x, int y, Consumer<PotteryWheelBlockEntity.ControlMode> onModeChanged) {
        super(x, y, 14, Icon.ENABLED, List.of(), () -> {});
        this.onModeChanged = onModeChanged;
    }

    public void setMode(PotteryWheelBlockEntity.ControlMode mode, Component tooltip) {
        this.mode = mode;
        setTooltip(tooltip);
    }

    public PotteryWheelBlockEntity.ControlMode getMode() {
        return mode;
    }

    @Override
    public void onPress() {
        onModeChanged.accept(PotteryWheelBlockEntity.ControlMode.byId(mode.ordinal() + 1));
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = width, h = height;
        boolean hovered = isHovered() && active;
        graphics.fill(x, y, x + w, y + h, BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hovered ? BG_HOVER : BG);
        if (mode == PotteryWheelBlockEntity.ControlMode.REDSTONE_ENABLED) {
            graphics.pose().pushPose();
            graphics.pose().translate(x + 2.0F, y + 2.0F, 0.0F);
            graphics.pose().scale(0.625F, 0.625F, 1.0F);
            graphics.renderItem(new ItemStack(Items.REDSTONE), 0, 0);
            graphics.pose().popPose();
            return;
        }
        int column = mode == PotteryWheelBlockEntity.ControlMode.DISABLED ? 6 : 7;
        graphics.blit(ICON_ATLAS, x + 2, y + 2, column * 9, 9, 9, 9, 81, 27);
    }
}
