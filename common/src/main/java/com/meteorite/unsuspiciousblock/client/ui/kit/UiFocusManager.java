package com.meteorite.unsuspiciousblock.client.ui.kit;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 焦点管理器：按宿主给出的**视觉顺序**持有一组 {@link UiFocusTarget}，唯一决定当前焦点，
 * 并提供 Tab / Shift+Tab 移动与 Enter / Space 激活。
 *
 * <p>与 {@link UiControlGroup} 的分工：控件组负责稳定 key 复用、层序命中与 tooltip；焦点与键盘
 * 导航集中在本类，原因是真实的 Tab 顺序会跨组件——参数浮层里的原生 {@code EditBox} 夹在两段控件之间，
 * 只有宿主按视觉顺序统一登记目标才能得到正确顺序。</p>
 *
 * <p>焦点只在键盘导航（{@link #keyPressed(int, int, int)} 的 Tab）与宿主显式 {@link #focusOn} 时改变：
 * 鼠标点击**不**夺取焦点，因此焦点轮廓只在键盘使用时出现，鼠标用户的画面与既有行为一致。</p>
 *
 * <p>内容或尺寸变化时调用 {@link #beginUpdate()} / {@link #add} / {@link #endUpdate()}：本帧未重新登记的
 * 目标会被移除，落在其上的焦点随之清除并通知 {@link Listener}；焦点目标未变时不会重复通知。</p>
 */
public final class UiFocusManager {
    /** 焦点变化通知：宿主据此把焦点目标滚动进视口、或同步旁白/提示。 */
    public interface Listener {
        void focusChanged(@Nullable UiFocusTarget previous, @Nullable UiFocusTarget current);
    }

    private final List<UiFocusTarget> targets = new ArrayList<>();
    private final Set<UiFocusTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    @Nullable private UiFocusTarget focused;
    @Nullable private Listener listener;
    private boolean updating;
    private boolean enterActivates = true;
    private boolean spaceActivates = true;

    // ---------- 目标登记 ----------

    /** 开始一次目标更新：随后未再 {@link #add} 的目标会在 {@link #endUpdate()} 时移除。 */
    public void beginUpdate() {
        seen.clear();
        updating = true;
    }

    /** 按视觉顺序登记一个目标；同一个目标在一次更新中重复登记只保留首次位置。 */
    public void add(UiFocusTarget target) {
        Objects.requireNonNull(target, "Focus target");
        if (!seen.add(target)) return;
        targets.add(target);
    }

    /** 结束目标更新：移除本帧未登记的目标，并在焦点目标失效时清除焦点。 */
    public void endUpdate() {
        if (updating) {
            targets.removeIf(target -> !seen.contains(target));
        }
        updating = false;
        seen.clear();
        refresh();
    }

    /** 清空目标与焦点；重新登记前焦点序列为空。 */
    public void clear() {
        targets.clear();
        seen.clear();
        setFocusedInternal(null);
    }

    public int size() { return targets.size(); }

    /** 只读目标列表（视觉顺序），供宿主与调试叠加层使用。 */
    public List<UiFocusTarget> targets() { return List.copyOf(targets); }

    // ---------- 焦点 ----------

    @Nullable public UiFocusTarget focused() {
        refresh();
        return focused;
    }

    /** 当前焦点在目标列表中的下标；无焦点时为 -1。 */
    public int focusedIndex() {
        refresh();
        return focused == null ? -1 : targets.indexOf(focused);
    }

    /**
     * 把焦点交给指定目标；目标不在列表、不可聚焦或与当前焦点相同时返回 {@code false}。
     * 传 {@code null} 等价于清除焦点。
     */
    public boolean focusOn(@Nullable UiFocusTarget target) {
        if (target == null) return clearFocus();
        if (!targets.contains(target) || !target.canFocus()) return false;
        if (focused == target) return true;
        setFocusedInternal(target);
        return true;
    }

    public boolean clearFocus() {
        if (focused == null) return false;
        setFocusedInternal(null);
        return true;
    }

    /** 焦点交给第一个可聚焦目标；没有可用目标时返回 false。 */
    public boolean focusFirst() {
        UiFocusTarget next = firstFocusable();
        return next != null && focusOn(next);
    }

    /** 在可聚焦目标间移动焦点（含环绕）。没有可聚焦目标时不消费。 */
    public boolean moveFocus(int delta) {
        refresh();
        List<UiFocusTarget> focusable = focusable();
        if (focusable.isEmpty()) return false;
        int current = focused == null ? -1 : focusable.indexOf(focused);
        int next = current < 0
                ? (delta < 0 ? focusable.size() - 1 : 0)
                : Math.floorMod(current + delta, focusable.size());
        return focusOn(focusable.get(next));
    }

    /** Enter / Space 激活当前焦点；无焦点或目标不消费时返回 false。 */
    public boolean activateFocused() {
        refresh();
        return focused != null && focused.activate();
    }

    /**
     * 只处理 Tab / Shift+Tab（移动焦点）与宿主允许的 Enter / Space（激活焦点），其余按键一律不消费。
     * ESC、上下键等语义属于模态宿主，搜索/编辑等语义属于原生输入控件。
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        refresh();
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            boolean backwards = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            return moveFocus(backwards ? -1 : 1);
        }
        if (enterActivates && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            return activateFocused();
        }
        if (spaceActivates && keyCode == GLFW.GLFW_KEY_SPACE) {
            return activateFocused();
        }
        return false;
    }

    /** 宿主是否把 Enter 交给焦点激活；模态用 Enter 关闭/确认时置 false。 */
    public void setEnterActivates(boolean enterActivates) { this.enterActivates = enterActivates; }

    /** 宿主是否把 Space 交给焦点激活。 */
    public void setSpaceActivates(boolean spaceActivates) { this.spaceActivates = spaceActivates; }

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    // ---------- 内部 ----------

    // 焦点目标可能因内容更新、禁用或隐藏而失效：每次交互入口先做一次廉价校验。
    private void refresh() {
        if (focused != null && (!targets.contains(focused) || !focused.canFocus())) {
            setFocusedInternal(null);
        }
    }

    private void setFocusedInternal(@Nullable UiFocusTarget next) {
        if (focused == next) return;
        UiFocusTarget previous = focused;
        if (previous != null) previous.setFocused(false);
        focused = next;
        if (next != null) next.setFocused(true);
        if (listener != null) listener.focusChanged(previous, next);
    }

    @Nullable
    private UiFocusTarget firstFocusable() {
        for (UiFocusTarget target : targets) {
            if (target.canFocus()) return target;
        }
        return null;
    }

    private List<UiFocusTarget> focusable() {
        List<UiFocusTarget> result = new ArrayList<>(targets.size());
        for (UiFocusTarget target : targets) {
            if (target.canFocus()) result.add(target);
        }
        return result;
    }
}
