package com.meteorite.unsuspiciousblock.client.ui.overlay;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/**
 * 浮层注册表：同一时刻至多一个浮层打开。打开期间它**吞掉全部鼠标与键盘输入**，并在所有面板与原生
 * 控件之上绘制，因此下层既点不到、也不会弹出提示——这是「鼠标穿透」那一类问题的正面解法，
 * 而不是给每条下层路径补守卫（原先的场景下拉就是漏了一条路径的守卫才穿透的）。
 *
 * <p>与排版层（{@code client/ui/kit}）刻意分离：本层只管“谁在当前接管输入”，不参与测量与排版。
 * 坐标一律是调用方（屏幕）的逻辑 GUI 坐标，屏幕在推入视口变换后渲染、并把鼠标换算成同一坐标后分发。
 */
public final class OverlayLayer {
    /** 与 {@code GuiGraphics.renderTooltipInternal} 相同的层高：GUI 里比它更高的只有别的 tooltip。 */
    private static final float TOOLTIP_Z = 400.0F;

    /** 浮层契约：自己负责绘制、命中与键盘处理；越界输入由本层丢弃，浮层不必自己兜底。 */
    public interface Overlay {
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick);

        boolean mouseClicked(double mouseX, double mouseY, int button);

        default boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return false;
        }

        default boolean mouseReleased(double mouseX, double mouseY, int button) {
            return false;
        }

        default boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
            return false;
        }

        boolean keyPressed(int keyCode, int scanCode, int modifiers);

        default boolean charTyped(char codePoint, int modifiers) {
            return false;
        }
    }

    /** 逻辑 GUI 坐标下的可用区域，由屏幕在视口变化时写入；浮层据此居中。 */
    private int width = 1;
    private int height = 1;
    @Nullable
    private Overlay open;

    public boolean isOpen() {
        return open != null;
    }

    public void setBounds(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void open(Overlay overlay) {
        this.open = overlay;
    }

    public void close() {
        this.open = null;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        if (open == null) return;
        // 与原生 tooltip 相同的做法：先落盘已有顶点，再把整层抬到 z=400。
        // 物品数量角标画在 z=200，若只抬到 200 会被它压住（旧的场景下拉就是这样漏的）。
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, TOOLTIP_Z);
        try {
            open.render(graphics, font, mouseX, mouseY, partialTick);
        } finally {
            graphics.pose().popPose();
        }
    }

    // 打开期间一律返回 true：浮层没处理的按键也被丢弃，避免下层误动（例如 ESC 不该关掉整本书）。
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (open == null) return false;
        open.mouseClicked(mouseX, mouseY, button);
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (open == null) return false;
        open.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (open == null) return false;
        open.mouseReleased(mouseX, mouseY, button);
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (open == null) return false;
        open.mouseScrolled(mouseX, mouseY, scrollY);
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (open == null) return false;
        open.keyPressed(keyCode, scanCode, modifiers);
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (open == null) return false;
        open.charTyped(codePoint, modifiers);
        return true;
    }
}
