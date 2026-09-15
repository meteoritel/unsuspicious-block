package com.meteorite.unsuspiciousblock.client.ui.support;

/**
 * GUI 自绘文本语义色表——与物品侧 {@code TooltipBuilder} 语义色一一对齐的 int 色实现。
 *
 * <p>架构约定（docs/dev/tooltip.md「GUI 文本规范」节）：语义色表是唯一语义层，
 * 共 9 个语义槽（TITLE / BODY / LABEL / HINT / POSITIVE / NEGATIVE / SEVERE / ACCENT / NAME），
 * 不同渲染介质各自提供实现：原版暗底 tooltip 走 {@code TooltipBuilder} 的 ChatFormatting 常量，
 * 羊皮纸 / 暗色两套 GUI 底色走本类的 int 主题常量组。</p>
 *
 * <p>仅覆盖文本语义色；边框、背景、进度条、选中态、滚动条等控件结构色
 * 不属文本语义，仍保留在各组件内部定义。暂无使用场景的语义槽预留不设值。</p>
 */
public final class UiTextPalette {

    /** 羊皮纸主题——考古笔记书页类界面（浅色底）的文本语义色。 */
    public static final class Parchment {
        /** 分区标题。 */
        public static final int TITLE = 0xFF3A2818;
        /** 正文 / 主内容文本。 */
        public static final int BODY = 0xFF5A422C;
        /** 次要信息 / 标签。 */
        public static final int LABEL = 0xFF7A6247;
        /** 弱化提示（比 LABEL 更浅一档）。 */
        public static final int HINT = 0xFF9A8A70;
        /** 正面 / 已完成 / 增益。 */
        public static final int POSITIVE = 0xFF3A8C3A;
        /** 警告 / 负面。 */
        public static final int NEGATIVE = 0xFFC06040;
        /** 特殊强调（收藏、强调数值等）。 */
        public static final int ACCENT = 0xFFC8A014;
        /** 物品名等主体名称。 */
        public static final int NAME = 0xFF3A2A1A;
        // SEVERE：羊皮纸暂无严重负面场景，预留不设值
    }

    /** 暗色主题——战利品表管理等暗底界面的文本语义色。 */
    public static final class Dark {
        /** 分区标题。 */
        public static final int TITLE = 0xFFFFFFFF;
        /** 正文 / 主内容文本。 */
        public static final int BODY = 0xFFE0E0E0;
        /** 次要信息 / 标签。 */
        public static final int LABEL = 0xFFB8B8B8;
        /** 弱化提示（未激活 / 未追踪等）。 */
        public static final int HINT = 0xFF999999;
        /** 正面 / 已追踪 / 成功。 */
        public static final int POSITIVE = 0xFF78D66A;
        /** 警告 / 负面 / 错误。 */
        public static final int NEGATIVE = 0xFFFF5555;
        /** 特殊强调（草稿状态、需注意项等）。 */
        public static final int ACCENT = 0xFFFFAA00;
        // NAME / SEVERE：暗色界面暂无对应场景，预留不设值
    }

    private UiTextPalette() {}
}
