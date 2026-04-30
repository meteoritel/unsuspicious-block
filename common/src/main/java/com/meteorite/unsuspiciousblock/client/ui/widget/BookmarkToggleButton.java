package com.meteorite.unsuspiciousblock.client.ui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** 书签形状切换按钮 —— 切换网格视图/详情视图 */
public class BookmarkToggleButton extends AbstractButton {
    private boolean toggled; // false=网格, true=详情
    private Runnable onToggle;

    public BookmarkToggleButton(int x, int y, int width, int height, Runnable onToggle) {
        super(x, y, width, height, Component.empty());
        this.toggled = false;
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
        this.toggled = !this.toggled;
        if (onToggle != null) {
            onToggle.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 书签形状: 底部尖角的长方形
        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        int h = this.height;
        int tabWidth = w / 3;

        int fillColor = toggled ? 0xFFD4B896 : (isHovered() ? 0xFFC8B090 : 0xFFA08060);
        int outlineColor = 0xFF5A422C;

        // 上半部分矩形
        guiGraphics.fill(x, y, x + w, y + h - 4, fillColor);
        // 下半部分三角形（尖角）
        guiGraphics.fill(x + tabWidth, y + h - 4, x + w - tabWidth, y + h, fillColor);
        // 填充中间三角缺口
        guiGraphics.fill(x + tabWidth, y + h - 5, x + w - tabWidth, y + h - 4, fillColor);

        // 描边
        guiGraphics.fill(x, y, x + w, y + 1, outlineColor);           // top
        guiGraphics.fill(x, y, x + 1, y + h - 4, outlineColor);       // left
        guiGraphics.fill(x + w - 1, y, x + w, y + h - 4, outlineColor); // right
        guiGraphics.fill(x + tabWidth, y + h - 4, x + w - tabWidth, y + h - 3, outlineColor); // bottom flat
        // 三角斜边（用台阶近似）
        for (int i = 0; i < 4; i++) {
            int inset = tabWidth - i;
            guiGraphics.fill(x + inset, y + h - 3 + i, x + w - inset, y + h - 2 + i, outlineColor);
        }
    }
}
