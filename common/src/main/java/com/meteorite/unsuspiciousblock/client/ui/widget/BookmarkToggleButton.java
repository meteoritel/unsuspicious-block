package com.meteorite.unsuspiciousblock.client.ui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/** 书签形状的Tab按钮 —— 带文字标签，由外部控制选中状态 */
public class BookmarkToggleButton extends AbstractButton {
    private boolean toggled;
    private final Component label;
    private final Runnable onToggle;

    public BookmarkToggleButton(int x, int y, int width, int height, Component label, Runnable onToggle) {
        super(x, y, width, height, Component.empty());
        this.toggled = false;
        this.label = label;
        this.onToggle = onToggle;
    }

    public boolean isToggled() {
        return toggled;
    }

    public void setToggled(boolean toggled) {
        this.toggled = toggled;
    }

    @Override
    public void onPress() {
        if (this.toggled) return; // 已选中不取消
        this.toggled = true;
        if (onToggle != null) {
            onToggle.run();
        }
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        int h = this.height;
        int tabWidth = w / 4;

        int fillColor = toggled ? 0xFFD4B896 : (isHovered() ? 0xFFC8B090 : 0xFFA08060);
        int outlineColor = 0xFF5A422C;
        int tabBottom = y + h - 3;

        // 上半部分矩形
        guiGraphics.fill(x, y, x + w, tabBottom, fillColor);
        // 下半部分三角形（尖角）
        guiGraphics.fill(x + tabWidth, tabBottom, x + w - tabWidth, y + h, fillColor);
        // 填充三角缺口
        guiGraphics.fill(x + tabWidth + 1, tabBottom - 1, x + w - tabWidth - 1, tabBottom, fillColor);

        // 描边
        //guiGraphics.fill(x, y, x + w, y + 1, outlineColor);
        //guiGraphics.fill(x, y, x + 1, tabBottom, outlineColor);
        //guiGraphics.fill(x + w - 1, y, x + w, tabBottom, outlineColor);
        //guiGraphics.fill(x + tabWidth, tabBottom, x + w - tabWidth, tabBottom + 1, outlineColor);
        for (int i = 0; i < 3; i++) {
            int inset = tabWidth - i;
            guiGraphics.fill(x + inset, tabBottom + 1 + i, x + w - inset, tabBottom + 2 + i, outlineColor);
        }

        // 文字标签
        Font font = Minecraft.getInstance().font;
        int textColor = toggled ? 0xFF3A2210 : 0xFF5A422C;
        int textWidth = font.width(label);
        int textX = x + (w - textWidth) / 2;
        int textY = y + 1 + (h - 8 - font.lineHeight) / 2;
        guiGraphics.drawString(font, label, textX, textY, textColor, false);
    }
}
