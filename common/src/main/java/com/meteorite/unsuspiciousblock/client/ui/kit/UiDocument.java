package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 声明式文档：内容版本门控构建，脏标记门控排版，绘制与命中复用同一份几何缓存。
 * 仅在客户端线程使用；视口及鼠标均使用调用方 GUI 坐标，外部 pose 仅支持正向轴对齐缩放和平移。
 */
public final class UiDocument {
    private static final int PADDING = 2;
    private static final int ICON_GAP = 3;
    /** 连线柱到行左边界（即缩进起点）的水平距离。 */
    private static final int BRANCH_GAP = 6;
    private final TextMeasurer measurer;
    private final UiTransform transform;
    private final UiMetrics metrics;
    private final int branchColor;
    private List<UiNode> content = List.of();
    private UiRect viewport = new UiRect(0, 0, 0, 0);
    private LayoutBlock[] blocks = new LayoutBlock[0];
    private boolean dirty = true;
    private boolean versioned;
    private long revision;
    private int contentWidth;
    private int contentHeight;

    public UiDocument(TextMeasurer measurer, boolean profiling) {
        this(measurer, new UiTransform(), 0, profiling);
    }

    public UiDocument(TextMeasurer measurer, UiTransform transform, int branchColor, boolean profiling) {
        this.measurer = Objects.requireNonNull(measurer);
        this.transform = transform;
        this.branchColor = branchColor;
        this.metrics = new UiMetrics(profiling);
    }

    public UiTransform transform() { return transform; }
    public UiMetrics metrics() { return metrics; }
    public UiRect viewport() { return viewport; }
    public int contentWidth() { layout(); return contentWidth; }
    public int contentHeight() { layout(); return contentHeight; }

    // 业务层合并目录、结果、选中项的版本；相同版本不会调用构建器。
    public void setContent(long newRevision, Supplier<List<UiNode>> builder) {
        if (versioned && revision == newRevision) return;
        long start = metrics.start();
        content = List.copyOf(builder.get());
        revision = newRevision;
        versioned = true;
        dirty = true;
        metrics.built(start);
    }

    // 适用于不使用版本门控的静态文档；构建器计时请使用上面的重载。
    public void setContent(List<UiNode> nodes) {
        long start = metrics.start();
        content = List.copyOf(nodes);
        versioned = false;
        dirty = true;
        metrics.built(start);
    }

    public void setViewport(int x, int y, int width, int height) {
        if (viewport.x() == x && viewport.y() == y && viewport.width() == width && viewport.height() == height) return;
        UiRect next = new UiRect(x, y, width, height);
        if (viewport.width() != width || viewport.height() != height) dirty = true;
        viewport = next;
        transform.setOrigin(x, y);
    }

    // 字体或资源重载后由宿主调用；内容改变时必须同时更新内容版本。
    public void invalidateLayout() { dirty = true; }

    public void layout() {
        if (!dirty) return;
        long start = metrics.start();
        int size = content.size();
        int[] parentOf = new int[size];
        boolean[] hasLaterSibling = new boolean[size];
        UiRect[] rowRects = new UiRect[size];
        int[] lastChildCenterY = new int[size];
        int[] lastIndexWithParent = new int[size];
        for (int i = 0; i < size; i++) {
            parentOf[i] = content.get(i) instanceof UiNode.Row row ? row.parentRow() : UiNode.NO_PARENT;
            lastChildCenterY[i] = -1;
            lastIndexWithParent[i] = -1;
        }
        // 反向扫一遍得出「该行之后是否还有同父兄弟」，续行竖线据此只在需要处落笔。
        for (int i = size - 1; i >= 0; i--) {
            int parent = parentOf[i];
            if (parent < 0 || parent >= size) continue;
            hasLaterSibling[i] = lastIndexWithParent[parent] >= 0;
            lastIndexWithParent[parent] = i;
        }
        List<LayoutBlock> next = new ArrayList<>(size);
        int y = 0;
        int widest = 0;
        for (int index = 0; index < size; index++) {
            UiNode node = content.get(index);
            if (node instanceof UiNode.Gap(int height)) {
                y += height;
            } else if (node instanceof UiNode.Row row) {
                BranchPaint branch = branchFor(index, row, y, parentOf, hasLaterSibling,
                        rowRects, lastChildCenterY);
                LayoutBlock block = layoutRow(row, index, y, branch);
                rowRects[index] = block.rect();
                int parent = parentOf[index];
                if (parent >= 0 && parent < size && rowRects[parent] != null) {
                    lastChildCenterY[parent] = block.rect().y() + block.rect().height() / 2;
                }
                next.add(block);
                y = block.rect().bottom();
                widest = Math.max(widest, block.rect().right());
            } else if (node instanceof UiNode.Divider(int color)) {
                UiRect rect = new UiRect(0, y, viewport.width(), 1);
                next.add(new LayoutBlock(rect, null, color, null));
                y++;
                widest = Math.max(widest, rect.width());
            } else if (node instanceof UiNode.Frame(var spec, var children)) {
                UiDocument child = new UiDocument(measurer, spec.transform(), spec.branchColor(), metrics.enabled());
                child.setViewport(0, y, spec.width(), spec.height());
                child.setContent(children);
                child.layout();
                next.add(new LayoutBlock(child.viewport(), null, 0, child));
                y += spec.height();
                widest = Math.max(widest, spec.width());
            }
        }
        blocks = next.toArray(LayoutBlock[]::new);
        contentWidth = widest;
        contentHeight = y;
        dirty = false;
        metrics.laidOut(start);
    }

    /**
     * 连线几何只在排版阶段算一次：折线从父行文字下方接到本行垂直中心，
     * 各祖先若有后续兄弟则在同一列落一条穿过本行高度的续行线（逐行相接即视觉连续）。
     */
    @Nullable
    private BranchPaint branchFor(int index, UiNode.Row row, int y, int[] parentOf,
                                  boolean[] hasLaterSibling, UiRect[] rowRects, int[] lastChildCenterY) {
        int parent = parentOf[index];
        // 只接受位于本行之前的父行：链严格递减，既排除自环也排除乱序输入。
        if (branchColor == 0 || parent < 0 || parent >= index) return null;
        UiRect parentRect = rowRects[parent];
        if (parentRect == null) return null;
        int trunkX = row.indent() - BRANCH_GAP;
        if (trunkX < 0) return null;
        int height = rowHeight(row);
        int centerY = y + height / 2;
        int previous = lastChildCenterY[parent];
        int from = previous >= 0 ? previous : parentRect.y() + measurer.lineHeight();
        int count = 0;
        for (int a = parent; a >= 0; a = parentOf[a]) {
            if (rowRects[a] != null && hasLaterSibling[a]) count++;
        }
        int[] continuations = new int[count * 3];
        int slot = 0;
        for (int a = parent; a >= 0; a = parentOf[a]) {
            UiRect rect = rowRects[a];
            if (rect == null || !hasLaterSibling[a]) continue;
            continuations[slot] = rect.x() - BRANCH_GAP;
            continuations[slot + 1] = y;
            continuations[slot + 2] = y + height;
            slot += 3;
        }
        return new BranchPaint(trunkX, from, centerY, row.indent(), continuations);
    }

    private int rowHeight(UiNode.Row row) {
        int height = measurer.lineHeight();
        for (int i = 0; i < row.icons().size(); i++) height = Math.max(height, row.icons().get(i).icon().height());
        return height + PADDING * 2;
    }

    private LayoutBlock layoutRow(UiNode.Row row, int index, int y, @Nullable BranchPaint branch) {
        int height = rowHeight(row);
        int textX = row.indent() + PADDING;
        int x = textX + measurer.width(row.text());
        UiTarget[] iconTargets = new UiTarget[row.icons().size()];
        UiIcon[] icons = new UiIcon[iconTargets.length];
        for (int i = 0; i < icons.length; i++) {
            UiNode.InlineIcon inline = row.icons().get(i);
            UiIcon icon = inline.icon();
            x += ICON_GAP;
            UiRect iconRect = new UiRect(x, y + (height - icon.height()) / 2, icon.width(), icon.height());
            iconTargets[i] = new UiTarget(UiTarget.Kind.ICON, index, inline.payload(), iconRect,
                    inline.tooltip(), inline.action());
            icons[i] = icon;
            x += icon.width();
        }
        UiRect rect = new UiRect(row.indent(), y, x + PADDING - row.indent(), height);
        UiTarget target = new UiTarget(UiTarget.Kind.ROW, index, row.payload(), rect, row.tooltip(), row.action());
        RowPaint paint = new RowPaint(row.text().getVisualOrderText(), row.color(), textX,
                y + (height - measurer.lineHeight()) / 2, target, icons, iconTargets, branch);
        return new LayoutBlock(rect, paint, 0, null);
    }

    public void render(GuiGraphics graphics, Font font) {
        layout();
        long start = metrics.start();
        if (viewport.width() > 0 && viewport.height() > 0) {
            UiTransform.enableScissor(graphics, viewport);
            transform.push(graphics);
            try {
                double top = transform.toLocalY(viewport.y());
                double bottom = transform.toLocalY(viewport.bottom());
                double left = transform.toLocalX(viewport.x());
                double right = transform.toLocalX(viewport.right());
                // 缩放小于 1 时线宽会掉到亚像素而消失，按档位取整保证屏幕上线宽不少于 1 像素。
                int thickness = Math.max(1, (int) Math.ceil(1.0 / transform.scale()));
                for (int i = firstBlockAt(top); i < blocks.length; i++) {
                    LayoutBlock block = blocks[i];
                    if (block.rect().y() >= bottom) break;
                    if (block.rect().right() <= left || block.rect().x() >= right) continue;
                    if (block.row() != null) renderRow(graphics, font, block.row(), left, top, right, bottom, thickness);
                    else if (block.frame() != null) block.frame().render(graphics, font);
                    else graphics.fill(block.rect().x(), block.rect().y(), block.rect().right(),
                                block.rect().bottom(), block.dividerColor());
                }
            } finally {
                // 先提交仍受裁剪约束的顶点，再恢复外层状态，避免延迟批次泄漏。
                graphics.pose().popPose();
                UiTransform.disableScissor(graphics);
            }
        }
        metrics.rendered(start);
    }

    private void renderRow(GuiGraphics graphics, Font font, RowPaint row,
                           double left, double top, double right, double bottom, int thickness) {
        if (row.branch() != null) renderBranch(graphics, row.branch(), thickness);
        graphics.drawString(font, row.text(), row.textX(), row.textY(), row.color(), false);
        for (int i = 0; i < row.icons().length; i++) {
            UiRect rect = row.iconTargets()[i].rect();
            if (rect.right() > left && rect.x() < right && rect.bottom() > top && rect.y() < bottom) {
                row.icons()[i].render(graphics, rect.x(), rect.y());
            }
        }
    }

    /** 原版进度界面同样用 1 像素填充画连线；这里沿用该做法，因此连线随框一起缩放平移。 */
    private void renderBranch(GuiGraphics graphics, BranchPaint branch, int thickness) {
        int[] continuations = branch.continuations();
        for (int i = 0; i < continuations.length; i += 3) {
            int x = continuations[i];
            graphics.fill(x, continuations[i + 1], x + thickness, continuations[i + 2], branchColor);
        }
        graphics.fill(branch.trunkX(), branch.verticalFromY(), branch.trunkX() + thickness,
                branch.verticalToY(), branchColor);
        graphics.fill(branch.trunkX(), branch.verticalToY(), branch.horizontalToX(),
                branch.verticalToY() + thickness, branchColor);
    }

    @Nullable
    public UiTarget hit(double mouseX, double mouseY) {
        layout();
        long start = metrics.start();
        UiTarget result = hitContent(mouseX, mouseY);
        metrics.hit(start);
        return result;
    }

    @Nullable
    private UiTarget hitContent(double mouseX, double mouseY) {
        if (!viewport.contains(mouseX, mouseY)) return null;
        double x = transform.toLocalX(mouseX);
        double y = transform.toLocalY(mouseY);
        int index = firstBlockAt(y);
        if (index >= blocks.length) return null;
        LayoutBlock block = blocks[index];
        if (!block.rect().contains(x, y)) return null;
        if (block.frame() != null) return block.frame().hit(x, y);
        RowPaint row = block.row();
        if (row == null) return null;
        for (int i = 0; i < row.iconTargets().length; i++) {
            if (row.iconTargets()[i].rect().contains(x, y)) return row.iconTargets()[i];
        }
        return row.target();
    }

    // 块按纵坐标排序，定位可见起点和命中行无需扫描整篇文档。
    private int firstBlockAt(double y) {
        int low = 0;
        int high = blocks.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (blocks[mid].rect().bottom() <= y) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    /** 排版缓存中的块，空白不进入绘制数组。 */
    private record LayoutBlock(@org.jetbrains.annotations.NotNull UiRect rect, @Nullable RowPaint row,
                               int dividerColor, @Nullable UiDocument frame) {}

    /** 行绘制数据与命中目标共用自然坐标，避免缩放时重建矩形。 */
    private record RowPaint(FormattedCharSequence text, int color, int textX, int textY,
                            UiTarget target, UiIcon[] icons, UiTarget[] iconTargets,
                            @Nullable BranchPaint branch) {}

    /**
     * 一行的连线几何：折线竖段从 {@code verticalFromY} 到 {@code verticalToY}，
     * 横段从 {@code trunkX} 到 {@code horizontalToX}；{@code continuations} 是
     * (x, y0, y1) 三元组序列，表示穿过本行的祖先续行线。
     */
    private record BranchPaint(int trunkX, int verticalFromY, int verticalToY,
                               int horizontalToX, int[] continuations) {}
}
