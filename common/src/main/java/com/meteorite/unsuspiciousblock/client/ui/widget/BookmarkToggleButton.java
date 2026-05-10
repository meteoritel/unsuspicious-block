package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 书签形状的Tab按钮 —— 带文字标签，由外部控制选中状态 */
public class BookmarkToggleButton extends AbstractButton {
    // 40x64 纵向四态切片：普通、悬停、选中、禁用
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/bookmark_tab.png");
    private static final int TEXTURE_WIDTH = 40;
    private static final int STATE_HEIGHT = 16;
    private static final int TEXTURE_HEIGHT = STATE_HEIGHT * 4;
    private static final int NORMAL_STATE_V = 0;
    private static final int HOVERED_STATE_V = STATE_HEIGHT;
    private static final int TOGGLED_STATE_V = STATE_HEIGHT * 2;
    private static final int DISABLED_STATE_V = STATE_HEIGHT * 3;

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

        int textureV = !this.active ? DISABLED_STATE_V
                : (this.toggled ? TOGGLED_STATE_V : (this.isHovered() ? HOVERED_STATE_V : NORMAL_STATE_V));
        guiGraphics.blit(TEXTURE, x, y, 0, textureV, w, h, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        Font font = Minecraft.getInstance().font;
        int textColor = !this.active ? 0xFF7A624A : (this.toggled ? 0xFF3A2210 : 0xFF5A422C);
        int textWidth = font.width(label);
        int textX = x + (w - textWidth) / 2;
        int textY = y + 1 + (h - 8 - font.lineHeight) / 2;
        guiGraphics.drawString(font, label, textX, textY, textColor, false);
    }
}
