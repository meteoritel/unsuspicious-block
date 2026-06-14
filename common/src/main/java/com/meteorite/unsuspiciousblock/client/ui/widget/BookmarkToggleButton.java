package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 右侧书签标签按钮 —— 附着在书本右边缘，三种视觉态：
 *   1. 正常：窄条(14px)贴在书边缘
 *   2. 悬浮：书签向右展开(30px)
 *   3. 选中：书签固定展开(30px)
 *
 * 按钮实际 hitbox 保持展开宽度(30px)，鼠标靠近即可触发悬浮展开。
 * 展开时直接在书签上绘制文字标签，不再使用 tooltip。
 */
public class BookmarkToggleButton extends AbstractButton {
    // 32x66 纵向三态切片：普通、悬停、选中（每态 22px 高）
    private static final int TEXTURE_WIDTH = 40;
    private static final int STATE_HEIGHT = 22;
    private static final int TEXTURE_HEIGHT = STATE_HEIGHT * 3;
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/bookmark_tab.png");
    private static final int NORMAL_STATE_V = 0;
    private static final int HOVERED_STATE_V = STATE_HEIGHT;
    private static final int TOGGLED_STATE_V = STATE_HEIGHT * 2;

    // 书签显示宽度
    private static final int BOOKMARK_TAB_WIDTH = 14;      // 正常态：贴在书边缘的窄条
    private static final int BOOKMARK_POPOUT_WIDTH = 38;   // 展开态：完整显示材质尖角
    private static final int BOOKMARK_HEIGHT = 22;

    private boolean toggled;
    private @Nullable Component tooltip;
    private final Runnable onToggle;

    public BookmarkToggleButton(int x, int y,
                                @Nullable Component tooltip, Runnable onToggle) {
        super(x, y, BOOKMARK_POPOUT_WIDTH, BOOKMARK_HEIGHT, Component.empty());
        this.toggled = false;
        this.tooltip = tooltip;
        this.onToggle = onToggle;
    }

    public boolean isToggled() {
        return toggled;
    }

    public void setToggled(boolean toggled) {
        this.toggled = toggled;
    }

    public void setTooltip(@Nullable Component tooltip) {
        this.tooltip = tooltip;
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
        boolean isExpanded = this.toggled || this.isHovered();
        int renderWidth = isExpanded ? BOOKMARK_POPOUT_WIDTH : BOOKMARK_TAB_WIDTH;

        int textureV = !this.active ? NORMAL_STATE_V
                : (this.toggled ? TOGGLED_STATE_V : (this.isHovered() ? HOVERED_STATE_V : NORMAL_STATE_V));
        int u = TEXTURE_WIDTH - renderWidth;
        guiGraphics.blit(TEXTURE, x, y, u, textureV, renderWidth, BOOKMARK_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        // 展开时在书签上直接绘制文字标签，无需 tooltip
        if (isExpanded && this.tooltip != null) {
            String text = this.tooltip.getString();
            int textWidth = Minecraft.getInstance().font.width(text);
            int textX = x + Math.max(3, (renderWidth - textWidth) / 2);
            int textY = y + (BOOKMARK_HEIGHT - 8) / 2;
            guiGraphics.drawString(Minecraft.getInstance().font, text, textX, textY, 0x3D2B1F, false);
        }
    }
}
