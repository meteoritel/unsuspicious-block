package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 复制坐标按钮——统一的渲染 + 命中检测 + 指令复制逻辑。
 * 用于日志列表页条目行与日志详情页坐标信息行。
 * 复制指令格式：/execute as @s in <维度注册名> run tp @s x (y+1) z
 */
public final class CopyCoordinateButton {
    public static final int WIDTH = JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH;
    public static final int HEIGHT = JournalLayout.LOG_ENTRY_COPY_BTN_HEIGHT;

    private CopyCoordinateButton() {
    }

    // 渲染按钮：暖色半透明背景 + 1px 边框 + 两重叠方块图标
    public static void render(GuiGraphics g, int btnX, int btnY, boolean hovered) {
        int w = WIDTH;
        int h = HEIGHT;
        int bg = hovered ? JournalLayout.LOG_ENTRY_COPY_BTN_BG_HOVER
                : JournalLayout.LOG_ENTRY_COPY_BTN_BG_NORMAL;
        g.fill(btnX, btnY, btnX + w, btnY + h, bg);
        // 边框（4 条 1px 线）
        g.fill(btnX, btnY, btnX + w, btnY + 1, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        g.fill(btnX, btnY + h - 1, btnX + w, btnY + h, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        g.fill(btnX, btnY, btnX + 1, btnY + h, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        g.fill(btnX + w - 1, btnY, btnX + w, btnY + h, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        // 图标：两个重叠的方块轮廓（复制符号）
        int iconColor = hovered ? JournalLayout.LOG_ENTRY_COPY_BTN_ICON_HOVER_COLOR
                : JournalLayout.LOG_ENTRY_COPY_BTN_ICON_COLOR;
        drawSquareOutline(g, btnX + 3, btnY + 2, 5, iconColor);
        drawSquareOutline(g, btnX + 6, btnY + 3, 5, iconColor);
    }

    // 命中检测
    public static boolean isHit(int btnX, int btnY, double mouseX, double mouseY) {
        return mouseX >= btnX && mouseX <= btnX + WIDTH
                && mouseY >= btnY && mouseY <= btnY + HEIGHT;
    }

    // 复制 /execute as @s in <dim> run tp @s x (y+1) z 到剪贴板，播放音效并通过 actionbar 反馈
    public static void copyCommand(ExcavationLogEntry entry) {
        var pos = entry.pos();
        ResourceLocation dimId = entry.dimensionId();
        String dimStr = dimId != null ? dimId.toString() : "minecraft:overworld";
        // y+1：传送到方块上方，避免卡在方块内
        String command = String.format("/execute as @s in %s run tp @s %d %d %d",
                dimStr, pos.getX(), pos.getY() + 1, pos.getZ());
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(command);
        // 播放原版 UI 按钮点击音效
        minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_copy_teleport_success", command), true);
        }
    }

    // 渲染按钮 tooltip（由外部在 super.render 之后调用）
    public static void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        Component tooltip = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_copy_teleport_tooltip");
        g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    // 绘制 1px 描边的正方形：size 为边长（含描边）
    private static void drawSquareOutline(GuiGraphics g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + 1, color);            // top
        g.fill(x, y + size - 1, x + size, y + size, color); // bottom
        g.fill(x, y, x + 1, y + size, color);            // left
        g.fill(x + size - 1, y, x + size, y + size, color); // right
    }
}
