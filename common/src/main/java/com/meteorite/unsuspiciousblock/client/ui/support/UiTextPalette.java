package com.meteorite.unsuspiciousblock.client.ui.support;

/**
 * GUI 自绘文本语义色表——与物品侧 {@code TooltipBuilder} 语义色一一对齐的 int 色实现。
 *
 * <p>架构约定（docs/dev/foundation/text-format.md 的「GUI 内文本规范」）：语义色表是唯一语义层，
 * 共 9 个语义槽（TITLE / BODY / LABEL / HINT / POSITIVE / NEGATIVE / SEVERE / ACCENT / NAME），
 * 不同渲染介质各自提供实现：原版暗底 tooltip 走 {@code TooltipBuilder} 的 ChatFormatting 常量，
 * 羊皮纸 / 暗色两套 GUI 底色走本类的 int 主题常量组。</p>
 *
 * <p>仅覆盖文本语义色；边框、背景、进度条、选中态、滚动条等控件结构色
 * 不属文本语义，仍保留在各组件内部定义。</p>
 *
 * <p>对比度台账（2026-09-28 复算，WCAG 2.x 相对亮度公式）：
 * 羊皮纸自绘文本不带文字阴影，取值对两种羊皮纸底色（#E8DCBC / #F2E5C6）都必须达到 4.5:1。
 * 本轮复算后 Parchment 组 9 个槽全部达标（区间 4.68:1 ~ 11.22:1），各槽精确比值见下方常量 javadoc。
 * Dark 组台账已定，本轮不动。</p>
 */
public final class UiTextPalette {

    /** 羊皮纸主题——考古笔记书页类界面（浅色底）的文本语义色。 */
    public static final class Parchment {
        /** 分区标题。#E8DCBC 10.29:1 / #F2E5C6 11.22:1。 */
        public static final int TITLE = 0xFF3A2818;
        /** 正文 / 主内容文本。#E8DCBC 6.84:1 / #F2E5C6 7.46:1。 */
        public static final int BODY = 0xFF5A422C;
        /**
         * 次要信息 / 标签。
         * <p>2026-09-28 由 0xFF7A6247 压暗为 0xFF6B573F：原值 #E8DCBC 4.20:1 / #F2E5C6 4.58:1，
         * 在较暗的羊皮纸底上不足 4.5:1；新值 #E8DCBC 5.03:1 / #F2E5C6 5.49:1。语义不变。
         */
        public static final int LABEL = 0xFF6B573F;
        /**
         * 弱化提示（比 LABEL 更弱一档的层级）。
         * <p>2026-09-28 由 0xFF9A8A70 压暗为 0xFF6A5A45：原值 #E8DCBC 2.47:1 / #F2E5C6 2.69:1，
         * 几乎不可读；新值 #E8DCBC 4.87:1 / #F2E5C6 5.31:1。
         * 饱和度（35%）低于 LABEL（41%）、明度相同，仍保留「比 LABEL 更弱」的观感层级。
         */
        public static final int HINT = 0xFF6A5A45;
        /**
         * 正面 / 已完成 / 增益。
         * <p>2026-09-28 由 0xFF3A8C3A 压暗为 0xFF37632F：本槽承载"已解锁/增益"这类必须读到的状态词，
         * 原值 #E8DCBC 3.08:1 / #F2E5C6 3.36:1 不达标；新值 #E8DCBC 5.15:1 / #F2E5C6 5.62:1。
         * 色相与饱和度不变（绿），仅压暗明度，语义不变。
         */
        public static final int POSITIVE = 0xFF37632F;
        /**
         * 警告 / 负面。
         * <p>2026-09-28 由 0xFFC06040 压暗为 0xFFA33F1E：原值 #E8DCBC 3.09:1 / #F2E5C6 3.37:1 不达标；
         * 新值 #E8DCBC 4.68:1 / #F2E5C6 5.11:1。与 {@link #SEVERE}（0xFF9A3520，5.30:1 / 5.78:1）保持层级差：
         * 本槽色相 15°、明度 64%，比 SEVERE 的 10° / 60% 更暖更亮，读作"警告"而非"严重"。
         */
        public static final int NEGATIVE = 0xFFA33F1E;
        /**
         * 特殊强调（收藏、强调数值等）。
         * <p>2026-09-28 由 0xFFC8A014 改为深金 0xFF7A5700：原值 #E8DCBC 1.81:1 / #F2E5C6 1.98:1，
         * 既作标记也作文字时都读不出来；新值 #E8DCBC 4.82:1 / #F2E5C6 5.26:1。
         * 色相 43°、饱和度 100%，与 TITLE(28°) / BODY(29°) / LABEL(33°) / HINT(34°) 的
         * 明度与饱和度都能区分，仍保留"强调"语义；该值与 {@code ItemGridPanel} 的概率型条件色同值，
         * 避免同一底色上出现两个"强调金"。
         */
        public static final int ACCENT = 0xFF7A5700;
        /** 物品名等主体名称。#E8DCBC 10.09:1 / #F2E5C6 11.01:1。 */
        public static final int NAME = 0xFF3A2A1A;
        /**
         * 严重负面 / 错误文案（如参数校验失败提示）。
         * <p>2026-09-28 设值：{@link #NEGATIVE} 作为正文级错误文字只有 3.09:1 / 3.37:1，不达标；
         * 本槽 #E8DCBC 5.30:1 / #F2E5C6 5.78:1。
         */
        public static final int SEVERE = 0xFF9A3520;
    }

    /** 暗色主题——战利品表管理等暗底界面的文本语义色（台账已定，不再改动）。 */
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
