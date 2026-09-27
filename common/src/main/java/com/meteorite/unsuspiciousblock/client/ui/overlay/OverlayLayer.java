package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusTarget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

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

        /** 关闭回调：浮层被关闭或替换时调用一次，用于释放输入捕获与引用。 */
        default void closed() {
        }
    }

    /** 逻辑 GUI 坐标下的可用区域，由屏幕在视口变化时写入；浮层据此居中。 */
    private int width = 1;
    private int height = 1;
    @Nullable
    private Overlay open;
    /** 打开当前浮层的可聚焦目标：关闭后焦点回到它，满足「关闭后焦点可恢复」。 */
    @Nullable
    private UiFocusTarget returnFocus;
    /**
     * 已经还给 opener 的焦点：页面侧可能没有焦点管理器（当前只有两个浮层与调试页有），
     * 因此需要宿主在「未被子层消费的鼠标操作」里调用 {@link #clearRestoredFocus()} 把它收掉，
     * 否则该控件会一直带着焦点轮廓。
     */
    @Nullable
    private UiFocusTarget restoredFocus;

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
        open(overlay, null);
    }

    /**
     * 打开浮层并记录「打开它的可聚焦目标」；关闭时焦点回到该目标。
     *
     * <p>替换已打开的浮层（切模态）时，未显式传入 opening 的一方继承上一层记录的返回焦点，
     * 因此模态链关闭后仍能回到最初的入口；返回焦点在浮层打开期间保持非聚焦，避免下层残留轮廓。</p>
     */
    public void open(Overlay overlay, @Nullable UiFocusTarget opener) {
        Objects.requireNonNull(overlay, "Overlay");
        clearRestoredFocus();
        Overlay replaced = this.open;
        // 只有**当前确实持有焦点**的入口才登记为返回焦点：鼠标点击不夺取焦点，
        // 因此鼠标打开的浮层关闭后不该把焦点（以及轮廓）留在入口按钮上；
        // 键盘导航到的入口（Tab 后按 Space/Enter）仍需在关闭后拿回焦点。
        UiFocusTarget nextReturn = opener != null && opener.isFocused() ? opener : returnFocus;
        if (replaced != null) replaced.closed();
        if (returnFocus != null && returnFocus != nextReturn) returnFocus.setFocused(false);
        this.open = overlay;
        this.returnFocus = nextReturn;
        if (this.returnFocus != null) this.returnFocus.setFocused(false);
    }

    public void close() {
        Overlay closing = this.open;
        this.open = null;
        if (closing != null) closing.closed();
        UiFocusTarget restore = this.returnFocus;
        this.returnFocus = null;
        if (restore != null && restore.canFocus()) {
            restore.setFocused(true);
            restoredFocus = restore;
        }
    }

    /**
     * 收掉上一次关闭浮层时还给 opener 的焦点。宿主应在未被子层消费的鼠标操作里调用：
     * 页面侧没有焦点管理器时，这是该轮廓唯一的清除时机（再次打开浮层也会清除）。
     */
    public void clearRestoredFocus() {
        UiFocusTarget restored = restoredFocus;
        restoredFocus = null;
        if (restored != null) restored.setFocused(false);
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
