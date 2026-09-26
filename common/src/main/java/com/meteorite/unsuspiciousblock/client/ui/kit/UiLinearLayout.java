package com.meteorite.unsuspiciousblock.client.ui.kit;

import java.util.List;
import java.util.Objects;

/**
 * 有界横纵布局：把子项按显式尺寸规则排成一行或一列，产出逻辑矩形，供宿主摆放控件与原生输入框。
 *
 * <p>主轴放不下时子项按 min 溢出而不是被压到 min 以下（调用方需保证容器足够大）；子项之间永不重叠。</p>
 *
 * <p>只提供有限规则——主轴 {@link SizeRule}（固定 / 内容自适应 / 占剩余）加 min·max 钳制，交叉轴同样是
 * 固定 / 内容 / 拉满，再配统一的 {@code spacing} 与 {@code padding}；不做任意嵌套容器、自动换行或
 * 响应式断点。「纵向容器里每行一个横向容器」是预期用法：子布局各自 {@link #setBounds(int, int, int, int)}
 * 到父布局给出的矩形即可，因此一层嵌套就能表达标签列 + 控件列的表格行。</p>
 *
 * <pre>
 * UiLinearLayout rows = new UiLinearLayout(Axis.VERTICAL, 0, 0);
 * rows.setChildren(List.of(Child.content(20), Child.content(20)));
 * rows.setBounds(x, y, width, height);
 * UiRect firstRow = rows.bounds(0);
 * </pre>
 *
 * <p>布局结果在内容或边界变化时重算，{@link #bounds(int)} 只读缓存；本类不含任何业务状态、
 * 不绘制、不分发输入，坐标一律为宿主 GUI 逻辑坐标。</p>
 */
public final class UiLinearLayout {
    /** 排列方向：子项沿该轴依次排列，另一轴为交叉轴。 */
    public enum Axis { VERTICAL, HORIZONTAL }

    /** 主轴尺寸规则。 */
    public enum SizeRule { FIXED, CONTENT, REMAIN }

    /**
     * 单个子项的尺寸规则。
     *
     * @param main     主轴规则
     * @param mainSize FIXED 的像素尺寸或 CONTENT 的已测尺寸；REMAIN 时忽略
     * @param minMain  主轴最小尺寸（含 REMAIN 分配结果）
     * @param maxMain  主轴最大尺寸
     * @param cross    交叉轴规则
     * @param crossSize 交叉轴 FIXED / CONTENT 的像素尺寸；REMAIN 时忽略
     * @param leading  本子项之前的主轴间距；负数表示沿用布局的默认 spacing
     */
    public record Child(SizeRule main, int mainSize, int minMain, int maxMain,
                        SizeRule cross, int crossSize, int leading) {
        public Child {
            Objects.requireNonNull(main);
            Objects.requireNonNull(cross);
            if (mainSize < 0 || crossSize < 0) throw new IllegalArgumentException("Negative child size");
            if (minMain < 0 || maxMain < minMain) throw new IllegalArgumentException("Invalid main clamp");
        }

        /** 主轴固定尺寸，交叉轴拉满。 */
        public static Child fixed(int size) {
            return new Child(SizeRule.FIXED, size, 0, Integer.MAX_VALUE, SizeRule.REMAIN, 0, -1);
        }

        /** 主轴使用调用方已测好的内容尺寸，交叉轴拉满。 */
        public static Child content(int measured) {
            return new Child(SizeRule.CONTENT, measured, measured, measured, SizeRule.REMAIN, 0, -1);
        }

        /** 主轴占剩余空间，交叉轴拉满。 */
        public static Child remain() { return remain(0, Integer.MAX_VALUE); }

        /** 主轴占剩余空间并钳制在 [min, max]。 */
        public static Child remain(int min, int max) {
            return new Child(SizeRule.REMAIN, 0, min, max, SizeRule.REMAIN, 0, -1);
        }

        /** 追加主轴最小/最大钳制。 */
        public Child mainRange(int min, int max) {
            return new Child(main, mainSize, min, max, cross, crossSize, leading);
        }

        /** 交叉轴改为固定尺寸。 */
        public Child crossFixed(int size) {
            return new Child(main, mainSize, minMain, maxMain, SizeRule.FIXED, size, leading);
        }

        /** 交叉轴改为已测的内容尺寸。 */
        public Child crossContent(int measured) {
            return new Child(main, mainSize, minMain, maxMain, SizeRule.CONTENT, measured, leading);
        }

        /** 本子项之前的主轴间距；用于同一行里宽度规则不同的列。 */
        public Child withLeading(int gap) {
            if (gap < 0) throw new IllegalArgumentException("Negative leading");
            return new Child(main, mainSize, minMain, maxMain, cross, crossSize, gap);
        }
    }

    private final Axis axis;
    private int spacing;
    private int padding;
    private List<Child> children = List.of();
    private UiRect[] rects = new UiRect[0];
    private int x;
    private int y;
    private int width;
    private int height;
    private int usedMain;

    public UiLinearLayout(Axis axis, int spacing, int padding) {
        this.axis = Objects.requireNonNull(axis);
        this.spacing = Math.max(0, spacing);
        this.padding = Math.max(0, padding);
    }

    public Axis axis() { return axis; }

    public int spacing() { return spacing; }

    public void setSpacing(int spacing) {
        int next = Math.max(0, spacing);
        if (next == this.spacing) return;
        this.spacing = next;
        layout();
    }

    public int padding() { return padding; }

    public void setPadding(int padding) {
        int next = Math.max(0, padding);
        if (next == this.padding) return;
        this.padding = next;
        layout();
    }

    /** 替换子项规则并立即重排；传入的列表按只读快照保存。 */
    public void setChildren(List<Child> children) {
        this.children = List.copyOf(children);
        this.rects = new UiRect[this.children.size()];
        layout();
    }

    /** 设置布局矩形（宿主 GUI 逻辑坐标）并立即重排；尺寸未变时不重复计算。 */
    public void setBounds(int x, int y, int width, int height) {
        int nextWidth = Math.max(0, width);
        int nextHeight = Math.max(0, height);
        if (this.x == x && this.y == y && this.width == nextWidth && this.height == nextHeight) return;
        this.x = x;
        this.y = y;
        this.width = nextWidth;
        this.height = nextHeight;
        layout();
    }

    public int size() { return children.size(); }

    /** 子项矩形；下标越界或尚未给出边界时返回零矩形，宿主无需自行判空。 */
    public UiRect bounds(int index) {
        if (index < 0 || index >= rects.length) return ZERO;
        return rects[index];
    }

    /** 主轴已占用长度（含两端内边距与全部间距），宿主据此算内容高度。 */
    public int usedMain() { return usedMain; }

    private static final UiRect ZERO = new UiRect(0, 0, 0, 0);

    // 先扣间距与固定/内容尺寸、并留出各 REMAIN 子项的 min，再把余量按 REMAIN 子项均分；
    // 每个结果都按 min/max 钳制，因此子项永不重叠，且容器够大时不会被压到 min 以下。
    private void layout() {
        int count = children.size();
        int mainExtent = Math.max(0, (axis == Axis.VERTICAL ? height : width) - padding * 2);
        int crossExtent = Math.max(0, (axis == Axis.VERTICAL ? width : height) - padding * 2);
        int totalLeading = 0;
        int fixedSum = 0;
        int remainCount = 0;
        int remainMinSum = 0;
        for (int i = 0; i < count; i++) {
            Child child = children.get(i);
            if (i > 0) totalLeading += leadingOf(child);
            if (child.main() == SizeRule.REMAIN) {
                remainCount++;
                remainMinSum += clampInt(child.minMain(), 0, child.maxMain());
            } else {
                fixedSum += clampInt(child.mainSize(), child.minMain(), child.maxMain());
            }
        }
        // 先给每个 REMAIN 子项留出 min，再把余量平分：这样「容器够大」时不会把某个子项压到 min 以下。
        int remainExtra = remainCount > 0
                ? Math.max(0, mainExtent - totalLeading - fixedSum - remainMinSum) / remainCount
                : 0;
        int cursor = (axis == Axis.VERTICAL ? y : x) + padding;
        int sumMain = 0;
        for (int i = 0; i < count; i++) {
            Child child = children.get(i);
            if (i > 0) cursor += leadingOf(child);
            int mainSize = child.main() == SizeRule.REMAIN
                    ? child.minMain() + remainExtra
                    : clampInt(child.mainSize(), child.minMain(), child.maxMain());
            mainSize = clampInt(mainSize, child.minMain(), child.maxMain());
            int crossSize = child.cross() == SizeRule.REMAIN
                    ? crossExtent
                    : Math.min(child.crossSize(), crossExtent);
            rects[i] = axis == Axis.VERTICAL
                    ? new UiRect(x + padding, cursor, crossSize, mainSize)
                    : new UiRect(cursor, y + padding, mainSize, crossSize);
            cursor += mainSize;
            sumMain += mainSize;
        }
        usedMain = padding * 2 + totalLeading + sumMain;
    }

    private int leadingOf(Child child) { return child.leading() >= 0 ? child.leading() : spacing; }

    private static int clampInt(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
