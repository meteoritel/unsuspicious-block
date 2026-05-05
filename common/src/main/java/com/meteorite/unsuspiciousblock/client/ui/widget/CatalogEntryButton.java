package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 目录条目 —— ObjectSelectionList 的自定义 Entry，支持超长文字滚动 */
@Deprecated
public class CatalogEntryButton extends ObjectSelectionList.Entry<CatalogEntryButton> {

    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");
    private static final int TEXT_INNER_PAD = 8;
    private static final int SCROLL_PAUSE_TICKS = 60;
    private static final int SCROLL_SPEED = 1;

    private final ResourceLocation id;
    private final Component displayName;
    private final boolean unlocked;
    private final Font font;
    private int scrollTicks;
    private boolean wasHovered;

    public CatalogEntryButton(ResourceLocation id, Component displayName, boolean unlocked, Font font) {
        this.id = id;
        this.displayName = displayName;
        this.unlocked = unlocked;
        this.font = font;
    }

    public ResourceLocation getId() {
        return id;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    @Override
    public @NotNull Component getNarration() {
        return displayName;
    }

    // 三态在材质中的 UV 偏移(px) 和 高度(px): 0=normal, 1=hovered, 2=selected
    private static final int[] STATE_V = {0, 20, 39};
    private static final int[] STATE_H = {19, 18, 18};

    @Override
    public void render(GuiGraphics guiGraphics, int index, int y, int x, int width, int height,
                       int mouseX, int mouseY, boolean hovered, float partialTick) {
        // 三态背景: 0=normal, 1=hovered, 2=selected
        int state = isFocused() ? 2 : (hovered ? 1 : 0);
        int rowHeight = JournalLayout.CATALOG_ROW_HEIGHT;
        guiGraphics.blit(ENTRY_TEXTURE,
                x, y, width, rowHeight,
                0, STATE_V[state],
                JournalLayout.CATALOG_TEXTURE_WIDTH, STATE_H[state],
                JournalLayout.CATALOG_TEXTURE_WIDTH, JournalLayout.CATALOG_TEXTURE_HEIGHT);

        String displayText = unlocked ? displayName.getString() : "???";
        int textColor = unlocked ? (isFocused() ? 0x7B3E18 : 0x5A422C) : 0x777777;
        int textMaxWidth = width - TEXT_INNER_PAD * 2;

        // 更新滚动计时
        if (!wasHovered && hovered) {
            scrollTicks = 0;
        }
        wasHovered = hovered;

        if (hovered) {
            scrollTicks++;
        }

        int textWidth = font.width(displayText);
        if (textWidth <= textMaxWidth) {
            // 文字不溢出，居中绘制
            int textX = x + (width - textWidth) / 2;
            guiGraphics.drawString(font, displayText, textX, y + 7, textColor, false);
        } else {
            // 文字溢出，scissor + 滚动绘制
            guiGraphics.enableScissor(x + TEXT_INNER_PAD, y, x + width - TEXT_INNER_PAD, y + rowHeight);
            int overflow = textWidth - textMaxWidth;
            int pauseWidth = 20;
            int cycleLength = overflow + pauseWidth;
            int offset = 0;
            if (hovered && cycleLength > 0) {
                offset = (scrollTicks * SCROLL_SPEED / 2) % (overflow + pauseWidth * 2);
                if (offset > overflow + pauseWidth) {
                    offset = overflow + pauseWidth * 2 - offset;
                }
                if (offset > overflow) {
                    offset = overflow;
                }
            }
            int textX = x + TEXT_INNER_PAD - offset;
            guiGraphics.drawString(font, displayText, textX, y + 7, textColor, false);
            guiGraphics.disableScissor();
        }
    }

    @Override
    public void renderBack(@NotNull GuiGraphics guiGraphics, int index, int y, int x, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
        if (isFocused()) {
            int rowHeight = JournalLayout.CATALOG_ROW_HEIGHT;
            int selColor = 0x22C8B090;
            guiGraphics.fill(x, y, x + width, y + rowHeight, selColor);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return button == 0; // 父类 AbstractSelectionList 自动处理选中和聚焦
    }
}
