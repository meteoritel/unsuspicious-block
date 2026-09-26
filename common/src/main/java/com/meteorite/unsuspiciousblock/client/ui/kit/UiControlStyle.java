package com.meteorite.unsuspiciousblock.client.ui.kit;

/**
 * 控件语义样式 token 组：把控件各交互状态的背景、焦点轮廓与滚动条结构色集中到一处，
 * 避免结构色散落到控件与各浮层的渲染点（对齐 {@code UiTextPalette} 的语义色分层约定）。
 *
 * <p>文本色仍由调用方从语义色表传入——本类只管**控件结构色**（背景、轮廓、滚动条），
 * 与 {@code docs/dev/internals/journal-ui-internals.md}「文本配色约束」的分工一致。</p>
 *
 * <p>{@link #PARCHMENT} 的普通/悬停背景与既有 {@code UiControl} 硬编码值逐像素相同，
 * 按下/选中/禁用/焦点是新增态，旧界面不会触发，因此换用本样式不改变既有画面。</p>
 */
public record UiControlStyle(int background, int hoverBackground, int pressedBackground,
                             int selectedBackground, int disabledBackground,
                             int focusOutline, int scrollbarTrack, int scrollbarThumb) {

    /** 控件交互状态；绘制时由控件按自身标志按优先级解析，不由调用方直接指定。 */
    public enum State { NORMAL, HOVER, PRESSED, SELECTED, DISABLED }

    /** 羊皮纸主题：考古笔记书页类浅色底界面。 */
    public static final UiControlStyle PARCHMENT = new UiControlStyle(
            0xFFF2E5C6, 0xFFE0C798, 0xFFD2B584, 0xFFE3CE96, 0xFFEDE4CB,
            0xFF3A6EA5, 0xFFDCD0B4, 0xFF8A7350);

    /** 暗色主题：战利品表管理等深色底界面；普通/悬停沿用该页既有的深灰底层次。 */
    public static final UiControlStyle DARK = new UiControlStyle(
            0xFF2A2A2A, 0xFF3C3C3C, 0xFF1E1E1E, 0xFF4A3E10, 0xFF232323,
            0xFF78B4FF, 0xFF1A1A1A, 0xFF6E6E6E);

    // 状态取背景色；优先级（禁用 > 按下 > 悬停 > 选中 > 普通）由控件在解析状态时保证。
    public int background(State state) {
        return switch (state) {
            case HOVER -> hoverBackground;
            case PRESSED -> pressedBackground;
            case SELECTED -> selectedBackground;
            case DISABLED -> disabledBackground;
            case NORMAL -> background;
        };
    }
}
