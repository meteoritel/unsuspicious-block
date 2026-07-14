package com.meteorite.unsuspiciousblock.loottable.simulation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 概率格式化工具 —— 将比例值格式化为人类可读的百分数字符串。
 * 使用 BigDecimal 保留百分号下 2 位有效数字。
 */
public final class ProbabilityFormat {

    private static final MathContext MC_2SIG = new MathContext(2, RoundingMode.HALF_UP);

    private ProbabilityFormat() {
    }

    /**
     * 将比例值（0.0~1.0）格式化为百分数字符串。
     * <ul>
     *   <li>≥1.0 → "100%"</li>
     *   <li>&lt;0.0001 → "&lt;0.01%"</li>
     *   <li>其他 → 百分数保留2位有效数字（如 "12%", "1.5%", "0.12%"）</li>
     * </ul>
     */
    public static String formatPercent(double fraction) {
        if (fraction >= 1.0) return "100%";
        if (fraction < 0.0001) return "<0.01%";
        double percent = fraction * 100.0;
        BigDecimal bd = new BigDecimal(percent, MC_2SIG);
        return bd.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * 解析概率字符串为比例值，用于排序等场景。
     * 返回 -1.0 表示无法解析（如 "?"）。
     */
    public static double parsePercentToFraction(String probability) {
        if (probability == null || probability.equals("?")) return -1.0;
        if (probability.equals("<0.01%")) return 0.0001; // 用于排序，给一个极小值
        if (probability.equals("100%")) return 1.0;
        try {
            String numPart = probability.replace("%", "").trim();
            double val = Double.parseDouble(numPart);
            return val / 100.0;
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }
}