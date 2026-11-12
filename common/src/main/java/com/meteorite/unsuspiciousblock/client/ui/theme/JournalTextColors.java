package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

/**
 * 羊皮纸书页专用的文本语义色，沿用书页无阴影配方（全部显式 8 位 ARGB，仅用于浅色羊皮纸底面）。
 *
 * <p>黑底 Tooltip 严禁消费本类或 {@link UiTextScheme#PARCHMENT}，统一使用 {@code TooltipBuilder} 语义常量。</p>
 */
public final class JournalTextColors {
    /** 物品名、时间及主读数等主体名称（与 {@link UiTextScheme#PARCHMENT} 标题墨色同值）。 */
    public static final int NAME = UiTextScheme.PARCHMENT.title();
    /** 严重负面与参数校验错误。 */
    public static final int SEVERE = 0xFF9A3520;
    /** 选中条目文本强调色（暖红棕）。 */
    public static final int SELECTED = 0xFF7B3E18;
    /** 子表与物品标签合集文本色（橄榄深绿）。 */
    public static final int COLLECTION = 0xFF3F5128;
    /** 模拟掉落概率常规数值色（暖褐）。 */
    public static final int PROB_DEFAULT = 0xFF7F4E1F;
    /** 概率型条件数值色（深金）。 */
    public static final int PROB_PROBABILISTIC = 0xFF7A5700;
    /** 运行时条件数值色（深橙红）。 */
    public static final int PROB_RUNTIME = 0xFF9E4326;
    /** 未计算、需条件或未知状态词颜色（深暖灰）。 */
    public static final int PROB_UNKNOWN = 0xFF4A4038;
    /** 书页内完成态正文本色（深森绿，满足 WCAG AA 对比度）。 */
    public static final int COMPLETED = 0xFF2F6E2D;

    private JournalTextColors() {
    }
}
