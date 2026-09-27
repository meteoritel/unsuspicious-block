package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 可聚焦目标适配器：kit 的焦点系统只通过本接口读可聚焦性与边界、写焦点、请求激活。
 *
 * <p>这样原生 {@code EditBox} 这类非 kit 控件也能按宿主给定的视觉顺序参与 Tab 导航，
 * 而 kit **不接管**它的文字编辑、剪贴板与输入法——那些仍归原版控件；同理，禁用、不可见、
 * 尚未注册的目标一律通过 {@link #canFocus()} 返回 {@code false} 退出焦点序列。</p>
 *
 * <p>实现方应保证 {@link #setFocused(boolean)} 只改绘制状态，不重建内容、不重排。</p>
 */
public interface UiFocusTarget {
    /** 当前是否可接收焦点（禁用、不可见或已卸载的目标返回 {@code false}）。 */
    boolean canFocus();

    /** 焦点状态变化通知；焦点只在键盘导航与宿主显式请求时改变。 */
    void setFocused(boolean focused);

    /** 当前是否持有焦点；模态入口据此判断「这个入口是不是键盘到达的」，避免鼠标点击也留下焦点轮廓。 */
    boolean isFocused();

    /** Enter / Space 的激活语义；不适用（例如原生输入框）时返回 {@code false} 不消费。 */
    boolean activate();

    /** 逻辑 GUI 坐标下的边界，供调试可视化与「滚动到焦点」使用。 */
    UiRect bounds();

    /** 可读名称：调试叠加层与 tooltip 回退用；{@code null} 表示没有名称。 */
    @Nullable Component accessibleName();
}
