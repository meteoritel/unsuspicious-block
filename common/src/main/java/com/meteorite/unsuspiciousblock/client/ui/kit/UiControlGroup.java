package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 控件组：按**显式层序**绘制控件、返回最上层命中目标与唯一 tooltip，并提供 Tab/Enter 键盘导航与按压捕获。
 *
 * <p>宿主稳定持有控件实例：内容或尺寸变化时调用 {@link #beginUpdate()} / {@link #obtain(Object)} /
 * {@link #endUpdate()}，同一个稳定 key 永远拿到同一个 {@link UiControl}；滚动、悬停等逐帧操作只改状态，
 * 不重建控件、也不重新测量文字。内容未变时宿主不必调用更新三件套。</p>
 *
 * <p>本类仍然**不做输入分发**：它把「是否消费」返回给宿主，由宿主按模态契约决定优先级
 * （原生 {@code EditBox}、框角按钮、文档命中的先后顺序由宿主维护）。坐标一律与控件矩形同坐标系，
 * 放在 {@code UiScrollView} 的平移 pose 内调用时传入内容坐标即可。</p>
 */
public final class UiControlGroup {
    /** 稳定 key → 控件；迭代顺序即创建顺序，也是绘制与命中的层序（后创建者在上层）。 */
    private final Map<Object, UiControl> byKey = new LinkedHashMap<>();
    private final List<UiControl> ordered = new ArrayList<>();
    private final Set<Object> seen = new HashSet<>();
    @Nullable private UiControl pressed;
    @Nullable private UiControl focused;
    private UiControlStyle style = UiControlStyle.PARCHMENT;
    private boolean keyboardNavigation = true;
    /** 是否处于 beginUpdate/endUpdate 之间；不在区间内时 endUpdate 不做裁剪，避免误清空。 */
    private boolean updating;

    // ---------- 稳定 key 复用 ----------

    /** 开始一次内容更新：随后未再 {@link #obtain(Object)} 的 key 会在 {@link #endUpdate()} 时移除。 */
    public void beginUpdate() {
        seen.clear();
        updating = true;
    }

    /** 按稳定 key 取控件；不存在时创建。必须在 {@link #beginUpdate()} 与 {@link #endUpdate()} 之间调用。 */
    public UiControl obtain(Object key) {
        Objects.requireNonNull(key, "Control key");
        seen.add(key);
        UiControl control = byKey.get(key);
        if (control == null) {
            control = new UiControl();
            control.setStyle(style);
            byKey.put(key, control);
            ordered.add(control);
        }
        return control;
    }

    /** 结束内容更新：丢弃本次未复用的控件，并清理落在它们上面的按压与焦点引用。 */
    public void endUpdate() {
        if (updating) {
            byKey.keySet().retainAll(seen);
            // 控件数量在几十量级，线性判定比再维护一份下标映射更省代码。
            ordered.removeIf(control -> !byKey.containsValue(control));
        }
        updating = false;
        seen.clear();
        refreshInteraction();
    }

    /** 清空全部控件与交互状态；重新填充前层序为空。 */
    public void clear() {
        byKey.clear();
        ordered.clear();
        seen.clear();
        pressed = null;
        focused = null;
    }

    public int size() { return ordered.size(); }
    public boolean isEmpty() { return ordered.isEmpty(); }

    /** 只读视图，按绘制层序（底层在前）；宿主据此在浮层里叠加原生控件。 */
    public List<UiControl> controls() { return List.copyOf(ordered); }

    // ---------- 样式 ----------

    /** 设置默认样式；已存在与新创建的控件一并套用。 */
    public void setStyle(UiControlStyle style) {
        this.style = Objects.requireNonNull(style);
        for (UiControl control : ordered) control.setStyle(style);
    }

    public UiControlStyle style() { return style; }

    // ---------- 绘制 / 命中 ----------

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        refreshInteraction();
        for (UiControl control : ordered) control.render(graphics, font, mouseX, mouseY);
    }

    /** 最上层命中控件；被上层控件遮挡时不会命中下层，与绘制层序一致。 */
    @Nullable public UiControl controlAt(double x, double y) {
        for (int i = ordered.size() - 1; i >= 0; i--) {
            UiControl control = ordered.get(i);
            if (control.hit(x, y) != null) return control;
        }
        return null;
    }

    /** 唯一命中目标；宿主用它派生 tooltip 或执行动作。 */
    @Nullable public UiTarget targetAt(double x, double y) {
        UiControl control = controlAt(x, y);
        return control == null ? null : control.hit(x, y);
    }

    /** 同一位置只能有一个 tooltip 来源：只画最上层命中且提示非空的目标。 */
    public void renderTooltip(GuiGraphics graphics, Font font, int x, int y) {
        UiTarget target = targetAt(x, y);
        if (target != null && !target.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, target.tooltip(), x, y);
        }
    }

    // ---------- 鼠标 ----------

    /**
     * 主键按下：命中即激活（与既有浮层「点击即执行」的行为一致），并记录按压捕获直到释放，
     * 便于宿主把拖动分派给滚动条等控件。返回是否消费。
     */
    public boolean mousePressed(double x, double y, int button) {
        if (button != 0) return false;
        UiControl control = controlAt(x, y);
        if (control == null) {
            clearPressed();
            return false;
        }
        pressed = control;
        control.setPressed(true);
        setFocused(control);
        control.activate();
        return true;
    }

    /** 释放主键：结束按压捕获；不在这里激活，避免拖动结束误触发。 */
    public void mouseReleased(int button) {
        if (button == 0) clearPressed();
    }

    public boolean isPressed() { return pressed != null; }

    /** 按压捕获是否仍在这个目标上；宿主据此判断拖动是拖控件还是拖下层内容。 */
    public boolean isPressCaptured(double x, double y) {
        return pressed != null && pressed.bounds().contains(x, y);
    }

    // ---------- 键盘与焦点 ----------

    /** 键盘导航默认开启；宿主在原生输入框获得焦点期间应关闭它，避免 Tab/空格冲突。 */
    public void setKeyboardNavigation(boolean keyboardNavigation) {
        this.keyboardNavigation = keyboardNavigation;
    }

    public boolean isKeyboardNavigationEnabled() { return keyboardNavigation; }

    /**
     * 处理 Tab / Shift+Tab（移动焦点）与 Enter / Space（激活焦点控件）。
     * 其余按键不消费，交回宿主——ESC、上下键等语义属于模态宿主。
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        refreshInteraction();
        if (!keyboardNavigation) return false;
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            boolean backwards = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            return moveFocus(backwards ? -1 : 1);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            return focused != null && focused.activate();
        }
        return false;
    }

    /** 在可聚焦控件间移动焦点（含环绕）。无可聚焦目标时不消费。 */
    public boolean moveFocus(int delta) {
        refreshInteraction();
        List<UiControl> focusable = focusable();
        if (focusable.isEmpty()) return false;
        int current = focused == null ? -1 : focusable.indexOf(focused);
        int next = current < 0
                ? (delta < 0 ? focusable.size() - 1 : 0)
                : Math.floorMod(current + delta, focusable.size());
        setFocused(focusable.get(next));
        return true;
    }

    /** 焦点交给第一个可聚焦控件；没有可选目标时返回 false。 */
    public boolean focusFirst() {
        refreshInteraction();
        List<UiControl> focusable = focusable();
        if (focusable.isEmpty()) return false;
        setFocused(focusable.getFirst());
        return true;
    }

    @Nullable public UiControl focused() {
        refreshInteraction();
        return focused;
    }

    public void clearFocus() { setFocused(null); }

    public void setFocused(@Nullable UiControl control) {
        if (focused == control) return;
        if (focused != null) focused.setFocused(false);
        focused = control != null && ordered.contains(control) && control.isFocusable() ? control : null;
        if (focused != null) focused.setFocused(true);
    }

    // 焦点与按压目标可能因内容更新、禁用或隐藏而失效：每次交互入口先做一次廉价校验。
    private void refreshInteraction() {
        if (focused != null && (!ordered.contains(focused) || !focused.isFocusable())) {
            focused.setFocused(false);
            focused = null;
        }
        if (pressed != null && (!ordered.contains(pressed) || !pressed.isFocusable())) {
            if (pressed.isPressed()) pressed.setPressed(false);
            pressed = null;
        }
    }

    private void clearPressed() {
        if (pressed != null) {
            pressed.setPressed(false);
            pressed = null;
        }
    }

    private List<UiControl> focusable() {
        List<UiControl> result = new ArrayList<>(ordered.size());
        for (UiControl control : ordered) {
            if (control.isFocusable()) result.add(control);
        }
        return result;
    }
}
