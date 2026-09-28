package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * 超宽文本的悬停滚动绘制——宽度够就照常画，超宽且悬停时在起点/终点各停顿一次后往返平移。
 *
 * <p>这是 kit 内**唯一**的滚动文字实现：{@code UiDocument} 的行文本与 {@code UiControl} 的标签都走它，
 * 旧的 {@code support/ScrollTextHelper} 仅作为 {@code String} 门面委托到此处，避免两份几何与节奏各写一遍。</p>
 *
 * <p>非交互场景（列表行等在固定宽度里直接画完、不接悬停滚动）用同类的
 * {@link #trimToWidth(Font, String, int)}：把超宽文本截断为带 ASCII 省略号的返回串，
 * 省略号口径集中在这里，避免各页面各写一份截断算法。</p>
 *
 * <p>裁剪使用 pose 感知的 scissor：调用点可能已经处于内容变换（缩放/平移）之中，
 * 而原版 {@code GuiGraphics.enableScissor} 只吃 GUI 逻辑坐标，因此先把矩形换算一次
 * （换算核心与 {@link UiTransform} 共用，见 {@code UiTransform.enableScissorInPose}）。
 * 与 {@link UiTransform#enableScissor} 的区别是有意的：这里向外取整且不提交绘制批次——
 * 文本走 {@code drawString}，它在非 managed 上下文里会立刻 endBatch，多一次 flush 只会多切一次批次；
 * 视口裁剪用于 blit 这类不自动 endBatch 的绘制，必须向内取整并在切换前提交。
 * 原版 scissor 是栈式交集，嵌套调用在退出时自动恢复外层裁剪。</p>
 */
public final class TextScroll {
    /** 起点与终点各停顿的位移量：太短会让往返显得急促。 */
    private static final float PAUSE_DISTANCE = 20.0F;
    /** 每 tick 的位移像素：越小越慢。 */
    private static final float SPEED = 0.35F;

    private TextScroll() {
    }

    /**
     * 绘制一段可滚动的文本（已缓存的视觉序列，kit 行文本走这条）。
     *
     * @param textWidth 已测得的文本宽度，避免重复测量
     * @param maxWidth  文本可用宽度（内容坐标）；{@code <= 0} 时直接跳过
     * @param hovered   是否处于悬停：只有悬停才推进位移，离开即回到起点
     * @param ticks     调用方自持的滚动计时；悬停期间应逐帧递增，离开后归零
     */
    public static void draw(GuiGraphics graphics, Font font, FormattedCharSequence text, int textWidth,
                            int x, int y, int maxWidth, int color, boolean hovered, int ticks) {
        if (maxWidth <= 0) return;
        if (textWidth <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        float offset = hovered ? offset(ticks, textWidth - maxWidth) : 0.0F;
        UiTransform.enableScissorInPose(graphics, x, y, x + maxWidth, y + font.lineHeight + 1);
        try {
            graphics.pose().pushPose();
            graphics.pose().translate(-offset, 0.0F, 0.0F);
            graphics.drawString(font, text, x, y, color, false);
            graphics.pose().popPose();
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * 绘制一段可滚动的文本（{@code String} 门面，供既有面板沿用）。内部按 {@link Font#width(String)} 测一次宽度；
     * 逐帧调用点若已缓存宽度，用带 {@code textWidth} 的重载避免每帧重新测量（原版测量会逐码点走一遍、无缓存）。
     *
     * @param centered 宽度足够时是否在可用宽度内居中
     */
    public static void draw(GuiGraphics graphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        if (maxWidth <= 0) return;
        draw(graphics, font, text, font.width(text), x, y, maxWidth, color, hovered, scrollTicks, centered);
    }

    /**
     * 绘制一段可滚动的文本（{@code String} 门面 + 已测宽度）：给逐帧调用点一条免测量的路径。
     *
     * <p>调用方必须自证宽度口径与 {@link Font#width(String)} 一致（同字体、同字符串、同语言），
     * 否则居中与滚动阈值会偏；宽度失效时（字体、语言、资源重载）由调用方重新测量。
     * kit 不为 {@code String} 建全局宽度缓存：那会把缓存键铺到无限多字符串上（见修复计划 D4）。</p>
     *
     * @param textWidth 调用方已测得的文本宽度
     * @param centered  宽度足够时是否在可用宽度内居中
     */
    public static void draw(GuiGraphics graphics, Font font, String text, int textWidth,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        if (maxWidth <= 0) return;
        if (textWidth <= maxWidth) {
            graphics.drawString(font, text, centered ? x + (maxWidth - textWidth) / 2 : x, y, color, false);
            return;
        }
        draw(graphics, font, FormattedCharSequence.forward(text, Style.EMPTY), textWidth,
                x, y, maxWidth, color, hovered, scrollTicks);
    }

    /**
     * 把超宽文本截断为带尾部省略号的**返回串**——悬停滚动的非交互替代：列表行用它在固定宽度里一次画完。
     *
     * <p>口径与宿主既有实现（管理页与语言选择列表）逐字一致：宽度足够（{@code font.width(value) <= maxWidth}）
     * 原样返回；连 {@code "..."} 都放不下时退化为纯宽度截断、不补省略号（补了它自己就越界）；
     * 其余情况截到 {@code maxWidth - font.width("...")} 再补省略号。
     * 省略号是 ASCII 的三个句点 {@code "..."}，不是单字符 {@code …}。</p>
     *
     * <p>宽度按 {@link Font#width(String)} 逐次测量（kit 不为 {@code String} 建全局宽度缓存）；
     * 返回新字符串，不改动入参；调用方的字体与语言口径必须与测量时一致。</p>
     *
     * @param maxWidth 可用宽度；{@code <= 0} 时返回空串
     */
    public static String trimToWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        if (maxWidth <= font.width(suffix)) {
            return font.plainSubstrByWidth(value, Math.max(0, maxWidth));
        }
        return font.plainSubstrByWidth(value, maxWidth - font.width(suffix)) + suffix;
    }

    // 起点与终点分别停顿，再以连续像素位移往返，避免整字符截取产生跳动。
    private static float offset(int scrollTicks, int overflow) {
        float phase = scrollTicks * SPEED;
        float period = overflow * 2.0F + PAUSE_DISTANCE * 2.0F;
        phase %= period;
        if (phase < PAUSE_DISTANCE) return 0.0F;
        phase -= PAUSE_DISTANCE;
        if (phase < overflow) return phase;
        phase -= overflow;
        if (phase < PAUSE_DISTANCE) return overflow;
        return overflow - (phase - PAUSE_DISTANCE);
    }
}
