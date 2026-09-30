package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 灯箱外壳：可复用的模态查看器——遮罩、内容视口、标题与描述、控制按钮组、键盘焦点与关闭语义。
 *
 * <p>本类**只做外壳与输入分派**，内容的尺寸、缩放、平移与裁剪由 {@link Content} 实现：
 * 图片用 {@code UiImageView}，场景条件树这类自绘内容由宿主适配器实现（它只要把视口、
 * 绘制、缩放、平移与命中按契约暴露出来）。因此同一个外壳既能看图片，也能放大整棵树。</p>
 *
 * <p>本类刻意**不依赖** {@code OverlayLayer}：宿主用 {@code LightboxOverlay} 适配器把它接进
 * 模态层，从而保持 kit 与宿主层单向依赖（overlay → kit）。坐标一律为宿主 GUI 逻辑坐标。</p>
 *
 * <p>控制栏默认使用 {@link UiControlStyle#DARK}（灯箱遮罩下是暗底），文本默认色由该结构底色反推；
 * 宿主可用 {@link #setStyle(UiControlStyle)} / {@link #setTextColor(int)} 覆盖。</p>
 *
 * <p>焦点由 {@link UiFocusManager} 按视觉顺序登记（关闭 → 上一张/下一张 → 缩小/放大/适应窗口），
 * Enter 与 Space 都用于激活焦点控件；ESC 与关闭按钮请求关闭，具体关闭动作由宿主传入的 {@code onClose} 执行。</p>
 */
public final class UiLightbox {
    private static final int MARGIN = 12;
    /** 底部控制栏高度：内容视口在它上方，控制栏不会被内容覆盖。 */
    private static final int BAR_HEIGHT = 22;
    private static final int TEXT_GAP = 2;
    private static final int BUTTON = 16;
    private static final int BUTTON_GAP = 2;
    /** 默认遮罩色：与既有浮层一致的半透明黑。 */
    private static final int DEFAULT_MASK = 0xC0101010;

    /** 默认结构样式：灯箱是暗底，控制栏按钮必须在暗底上可辨（暗底 + 近白字曾约 1.06:1）。 */
    private static final UiControlStyle DEFAULT_STYLE = UiControlStyle.DARK;
    /** 反推文本色的两个候选：实际取与结构底色对比度更高的一档。 */
    private static final int TEXT_ON_DARK = 0xFFF0F0F0;
    private static final int TEXT_ON_LIGHT = 0xFF1A1A1A;

    /** 宿主提供的本地化文案；kit 自身不持有任何文案 key。 */
    public interface Labels {
        Component close();
        Component zoomIn();
        Component zoomOut();
        Component fit();
        /** 图集导航按钮；无图集时不会调用。 */
        Component previous();
        Component next();
        /** 「当前 / 总数」；无图集时不会调用。 */
        Component position(int index, int total);

        // 缩放读数格式；默认沿用既有画面（"100%"），宿主可覆盖以本地化读数。
        // 以 default 方法新增：既有 Labels 实现无需改动即可编译。
        default Component zoomReadout(int percent) {
            return Component.literal(percent + "%");
        }
    }

    /** 图集：宿主持有图片列表与当前下标，灯箱只负责显示与请求切换。 */
    public interface Gallery {
        int index();
        int total();
        /** 宿主据此换掉内容（{@link #setContent(Content)}）并更新自己的下标。 */
        void navigate(int delta);
    }

    /**
     * 内容契约：视口由灯箱写入，绘制/命中/缩放/平移由实现方负责。
     * 所有坐标都是宿主 GUI 逻辑坐标；实现方必须把绘制严格裁剪在视口内。
     */
    public interface Content {
        /** 内容原始尺寸，用于 fit 与调试读数；**未知时返回 0，外壳会据此把 fit 延后到尺寸就绪那一帧**。 */
        int contentWidth();
        int contentHeight();

        /**
         * 视口变化（首次布局、resize）时写入。实现方据此**重新钳制平移并保持已有缩放**；
         * 首次布局与换内容时灯箱会紧接着调用一次 {@link #fit()}，因此不需要在 setViewport 里主动复位。
         */
        void setViewport(UiRect viewport);

        /** 在视口内绘制内容。 */
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY);

        /** 滚轮缩放，锚点为宿主 GUI 坐标；返回是否消费。 */
        boolean zoom(double amount, double anchorX, double anchorY);

        /** 以视口中心缩放：direction &lt; 0 缩小、&gt; 0 放大。 */
        boolean zoomBy(int direction);

        /** 适应窗口（复位）。 */
        void fit();

        /** 拖动平移，参数为屏幕像素增量。 */
        boolean panBy(double screenDx, double screenDy);

        /** 主键按下（内容区域）：开始拖动。 */
        boolean mousePressed(double x, double y, int button);

        /** 主键释放：结束拖动。 */
        boolean mouseReleased(double x, double y, int button);

        /** 当前缩放百分比，用于读数；不适用时返回 100。 */
        int zoomPercent();

        /** 内容的命中目标（例如树里的行）；没有可命中内容时返回 null。 */
        @Nullable UiTarget hit(double x, double y);

        /** 缺图/资源不可用时的占位文案；{@code null} 表示内容正常。 */
        @Nullable Component placeholder();

        /** 资源重载通知：丢弃尺寸与纹理派生缓存。 */
        default void invalidateResources() {
        }
    }

    private final Labels labels;
    private final Runnable onClose;
    private final UiControlGroup controls = new UiControlGroup();
    private final UiFocusManager focus = new UiFocusManager();
    private Content content;
    @Nullable private UiNineSlice surface;
    @Nullable private Component barHint;
    private java.util.Map<String, UiIcon> icons = java.util.Map.of();
    @Nullable private Gallery gallery;
    @Nullable private Font font;
    @Nullable private Component title;
    @Nullable private Component description;
    /** 文本色：默认按默认结构底色的相对亮度反推；调用方 setTextColor 后固定，不再跟随样式。 */
    private int textColor = readableTextOn(DEFAULT_STYLE.background());
    private boolean textColorPinned;
    private int maskColor = DEFAULT_MASK;
    private boolean maskClickCloses;
    private boolean dirty = true;
    /** 需要一次 fit：首次布局与换内容后为真，resize 不做 fit（保留用户缩放）。 */
    private boolean needFit = true;
    private int width = 1;
    private int height = 1;
    private int panelX;
    private int panelY;
    private int panelWidth = 1;
    private int panelHeight = 1;
    private UiRect viewport = new UiRect(0, 0, 0, 0);
    private int displayedZoom = Integer.MIN_VALUE;
    /** 缩放读数：只在变化时重建字符串与宽度，避免逐帧拼接与测量。 */
    private String zoomText = "100%";
    private int zoomTextWidth;
    /** 图集页码读数：同样只在 index/total 变化时重建。 */
    private int displayedIndex = Integer.MIN_VALUE;
    private int displayedTotal = Integer.MIN_VALUE;
    private Component positionText = Component.empty();

    public UiLightbox(Labels labels, Content content, Runnable onClose) {
        this.labels = Objects.requireNonNull(labels);
        this.content = Objects.requireNonNull(content);
        this.onClose = Objects.requireNonNull(onClose);
        // 构造期显式注入暗底：UiControlGroup 的默认样式是浅底 PARCHMENT，灯箱不沿用该默认值。
        // 调用方仍可用 setStyle 覆盖，覆盖后文本色未固定时会随之重算。
        this.controls.setStyle(DEFAULT_STYLE);
        // 灯箱里 Enter 没有其它语义，因此与 Space 一起用于激活焦点控件。
        focus.setEnterActivates(true);
        focus.setSpaceActivates(true);
    }

    // ---------- 宿主配置 ----------

    /** 换内容（切换图集或换内容类型）；视口尺寸不变，但会重新求 fit。 */
    public void setContent(Content content) {
        this.content = Objects.requireNonNull(content);
        this.displayedZoom = Integer.MIN_VALUE;
        this.needFit = true;
        this.dirty = true;
    }

    public void setGallery(@Nullable Gallery gallery) {
        this.gallery = gallery;
        this.displayedIndex = Integer.MIN_VALUE;
        this.displayedTotal = Integer.MIN_VALUE;
        this.dirty = true;
    }

    /** 标题与描述由内容决定时可不设置；设置后显示在内容视口上方。 */
    public void setText(@Nullable Component title, @Nullable Component description) {
        this.title = title == null ? null : title.copy();
        this.description = description == null ? null : description.copy();
        this.dirty = true;
    }

    /**
     * 控件结构色；默认 {@link UiControlStyle#DARK}（灯箱是暗底）。设置后对已有与后续控件一并生效；
     * 文本色未被 {@link #setTextColor(int)} 固定时，会按新底色的相对亮度重新反推。
     */
    public void setStyle(UiControlStyle style) {
        UiControlStyle next = Objects.requireNonNull(style);
        this.controls.setStyle(next);
        if (!textColorPinned) applyTextColor(readableTextOn(next.background()));
    }

    /** 固定文本色；此后 {@link #setStyle(UiControlStyle)} 不再改写它。 */
    public void setTextColor(int textColor) {
        this.textColorPinned = true;
        applyTextColor(textColor);
    }

    // 控件标签色只在配置时写入控件，因此改色后要标脏让下一帧重配一次（几何不变，只是同一 key 重配）。
    private void applyTextColor(int color) {
        if (this.textColor == color) return;
        this.textColor = color;
        this.dirty = true;
    }

    public void setMaskColor(int maskColor) { this.maskColor = maskColor; }

    public void setSurface(@Nullable UiNineSlice surface) {
        this.surface = surface;
        this.dirty = true;
    }

    public void setIcons(java.util.Map<String, UiIcon> icons) {
        this.icons = java.util.Map.copyOf(icons);
        this.dirty = true;
    }

    public void setBarHint(@Nullable Component hint) { this.barHint = hint; }

    /** 点遮罩是否关闭；默认 false，避免拖动图片时误触退出。 */
    public void setMaskClickCloses(boolean maskClickCloses) { this.maskClickCloses = maskClickCloses; }

    public UiRect viewport() { return viewport; }

    /**
     * 宿主可用区域（逻辑 GUI 坐标，左上角为原点）；由模态适配层在每次渲染前写入。
     * 负宽高按 1 钳制：外壳至少要有一像素才排得出面板与内容视口。
     */
    public void setBounds(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (this.width == w && this.height == h) return;
        this.width = w;
        this.height = h;
        this.dirty = true;
    }

    /** 资源重载：内容自行丢弃派生缓存并重新求 fit。 */
    public void invalidateResources() {
        content.invalidateResources();
        displayedZoom = Integer.MIN_VALUE;
        dirty = true;
    }

    /** 浮层关闭回调：释放按压捕获、结束内容拖动并清焦点；宿主复用视图时不会残留拖动状态。 */
    public void onClosed() {
        controls.mouseReleased(0);
        content.mouseReleased(0.0, 0.0, 0);
        focus.clearFocus();
    }

    // ---------- 绘制 ----------

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        this.font = font;
        layout(font);
        graphics.fill(0, 0, width, height, maskColor);
        if (surface != null) surface.render(graphics,
                new UiRect(panelX - 6, panelY - 6, panelWidth + 12, panelHeight + 12));
        if (title != null) {
            graphics.drawString(font, title, panelX, panelY, textColor, false);
        }
        if (description != null) {
            graphics.drawString(font, description, panelX, panelY + font.lineHeight + TEXT_GAP, textColor, false);
        }
        content.render(graphics, font, mouseX, mouseY);
        Component placeholder = content.placeholder();
        if (placeholder != null) {
            int textWidth = font.width(placeholder);
            graphics.drawString(font, placeholder,
                    viewport.x() + (viewport.width() - textWidth) / 2,
                    viewport.y() + (viewport.height() - font.lineHeight) / 2, textColor, false);
        }
        renderBar(graphics, font);
        controls.render(graphics, font, mouseX, mouseY);
        // 同一位置只允许一个 tooltip 来源：控件优先，没有控件命中时才问内容（例如条件树里的行）。
        // 命中只查一次：原实现先用 targetAt 判空、再交给 renderTooltip 重扫同一坐标。
        UiTarget hoveredControl = controls.targetAt(mouseX, mouseY);
        if (hoveredControl == null) {
            UiTarget target = content.hit(mouseX, mouseY);
            if (target != null && !target.tooltip().isEmpty()) {
                graphics.renderComponentTooltip(font, target.tooltip(), mouseX, mouseY);
            }
        } else if (!hoveredControl.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, hoveredControl.tooltip(), mouseX, mouseY);
        }
    }

    // 底部控制栏：左侧图集导航与页码、右侧缩放读数与缩放/适应窗口按钮；内容视口在它上方，因此不会被覆盖。
    private void renderBar(GuiGraphics graphics, Font font) {
        int y = panelY + panelHeight - BAR_HEIGHT + 3;
        int right = panelX + panelWidth;
        // 读数画在按钮组左侧/右侧；面板过窄时宁可不画，也不让两段文字重叠。
        int positionX = panelX + (BUTTON + BUTTON_GAP) * 2 + 4;
        int zoomX = right - (BUTTON * 3 + BUTTON_GAP * 2) - 6 - zoomTextWidth;
        if (surface != null) zoomX = right - 84 + (42 - zoomTextWidth) / 2;
        // 面板过窄时宁可不画读数；阈值按「右侧有没有页码」决定，无图集时不因页码位置白让出空间。
        boolean withGallery = gallery != null && gallery.total() > 1;
        if (zoomX < (withGallery ? positionX + 8 : panelX)) return;
        if (gallery != null && gallery.total() > 1) {
            graphics.drawString(font, positionText, positionX, y, textColor, false);
        }
        graphics.drawString(font, zoomText, zoomX, y, textColor, false);
        if (barHint != null && !withGallery && font.width(barHint) < right - 112 - panelX) {
            graphics.drawString(font, barHint, panelX, y, textColor, false);
        }
    }

    // ---------- 布局 ----------

    private void layout(Font font) {
        if (!dirty) {
            applyDeferredFit();
            refreshPositionText();
            refreshZoomText();
            return;
        }
        dirty = false;
        panelX = MARGIN;
        panelY = MARGIN;
        panelWidth = Math.max(1, width - MARGIN * 2);
        panelHeight = Math.max(1, height - MARGIN * 2);
        int textHeight = 0;
        if (title != null) textHeight += font.lineHeight + TEXT_GAP;
        if (description != null) textHeight += font.lineHeight + TEXT_GAP;
        if (surface != null) textHeight = Math.max(BUTTON + 4, textHeight);
        viewport = new UiRect(panelX, panelY + textHeight, panelWidth,
                Math.max(1, panelHeight - textHeight - BAR_HEIGHT));
        content.setViewport(viewport);
        applyDeferredFit();

        int barY = panelY + panelHeight - BAR_HEIGHT + 3;
        int right = panelX + panelWidth;
        boolean withGallery = gallery != null && gallery.total() > 1;
        controls.beginUpdate();
        focus.beginUpdate();
        UiControl close = place(font, "close", Component.literal("×"), labels.close(),
                right - BUTTON, panelY - 2);
        UiControl previous = withGallery
                ? place(font, "previous", Component.literal("◀"), labels.previous(), panelX, barY) : null;
        UiControl next = withGallery
                ? place(font, "next", Component.literal("▶"), labels.next(),
                        panelX + BUTTON + BUTTON_GAP, barY) : null;
        UiControl fit = place(font, "fit", Component.literal("↺"), labels.fit(),
                right - BUTTON, barY);
        UiControl zoomIn = place(font, "zoom_in", Component.literal("+"), labels.zoomIn(),
                surface == null ? right - BUTTON * 2 - BUTTON_GAP : right - 38, barY);
        UiControl zoomOut = place(font, "zoom_out", Component.literal("−"), labels.zoomOut(),
                surface == null ? right - BUTTON * 3 - BUTTON_GAP * 2 : right - 104, barY);
        // Tab 顺序 = 视觉顺序：关闭在右上角，其余按控制栏从左到右。
        focus.add(close);
        if (previous != null) focus.add(previous);
        if (next != null) focus.add(next);
        focus.add(zoomOut);
        focus.add(zoomIn);
        focus.add(fit);
        controls.endUpdate();
        focus.endUpdate();
        displayedZoom = Integer.MIN_VALUE;
        displayedIndex = Integer.MIN_VALUE;
        displayedTotal = Integer.MIN_VALUE;
        refreshPositionText();
        // 宽度由 refreshZoomText 在读数变化时测一次；此处不再重复测量同一字符串。
        refreshZoomText();
    }

    private UiControl place(Font font, String key, Component symbol, Component name, int x, int y) {
        UiControl control = controls.obtain(key);
        control.setBounds(x, y, BUTTON, BUTTON);
        UiIcon icon = icons.get(key);
        control.configure(font, icon == null ? symbol : Component.empty(), textColor, icon, List.of(name), () -> {
            switch (key) {
                case "close" -> onClose.run();
                case "previous" -> navigate(-1);
                case "next" -> navigate(1);
                case "zoom_in" -> content.zoomBy(1);
                case "zoom_out" -> content.zoomBy(-1);
                case "fit" -> content.fit();
                default -> {
                }
            }
        });
        // 符号本身不具名：把本地化名称显式给焦点调试与旁白。
        control.setAccessibleName(name);
        return control;
    }

    private void navigate(int delta) {
        if (gallery == null || gallery.total() <= 1) return;
        gallery.navigate(delta);
        dirty = true;
    }

    /*
     * 内容尺寸未知时不能 fit：条件树这类内容要到 render 阶段才把自己装进文档，
     * 此刻空文档的 contentWidth/contentHeight 为 0，任何档位都「放得下」，会误选最大档位。
     * 因此把 fit 留到内容就位后的某一帧（每帧检查一次，成本只是两次 int 读取）。
     */
    private void applyDeferredFit() {
        if (!needFit) return;
        if (content.contentWidth() <= 0 || content.contentHeight() <= 0) return;
        needFit = false;
        content.fit();
    }

    // 图集页码读数只在 index/total 变化时重建。
    private void refreshPositionText() {
        if (gallery == null || gallery.total() <= 1) return;
        if (gallery.index() == displayedIndex && gallery.total() == displayedTotal) return;
        displayedIndex = gallery.index();
        displayedTotal = gallery.total();
        positionText = labels.position(displayedIndex + 1, displayedTotal);
    }

    // 缩放读数只在变化时重建字符串与宽度；格式由 Labels.zoomReadout 提供，kit 不再硬编码 "%" 拼接。
    private void refreshZoomText() {
        int percent = content.zoomPercent();
        if (percent == displayedZoom) return;
        displayedZoom = percent;
        zoomText = labels.zoomReadout(percent).getString();
        zoomTextWidth = font == null ? 0 : font.width(zoomText);
    }

    // ---------- 输入 ----------

    public boolean mouseClicked(double x, double y, int button) {
        layoutIfPossible();
        // 鼠标点击不夺取焦点：按下就收掉键盘焦点，避免轮廓留在被点过的按钮上。
        focus.clearFocus();
        if (controls.mousePressed(x, y, button)) return true;
        if (viewport.contains(x, y)) return content.mousePressed(x, y, button);
        if (maskClickCloses) onClose.run();
        return true;
    }

    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        layoutIfPossible();
        return content.panBy(dragX, dragY);
    }

    public boolean mouseReleased(double x, double y, int button) {
        layoutIfPossible();
        controls.mouseReleased(button);
        return content.mouseReleased(x, y, button);
    }

    public boolean mouseScrolled(double x, double y, double amount) {
        layoutIfPossible();
        // 指针不在内容视口内不做缩放；输入仍由模态层吞掉，不会漏到下层。
        return viewport.contains(x, y) && content.zoom(amount, x, y);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        layoutIfPossible();
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose.run();
            return true;
        }
        if (gallery != null && gallery.total() > 1) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
                navigate(-1);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
                navigate(1);
                return true;
            }
        }
        return focus.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    // 文本默认色从结构底色反推：在近白/近黑两个候选里取 WCAG 对比度更高的一档，
    // 这样默认暗底（DARK）与调用方自行换的浅底都能保持可读，而不是固定用近白色。
    private static int readableTextOn(int background) {
        double backgroundLuminance = relativeLuminance(background);
        double onDark = contrast(TEXT_ON_DARK, backgroundLuminance);
        double onLight = contrast(TEXT_ON_LIGHT, backgroundLuminance);
        return onDark >= onLight ? TEXT_ON_DARK : TEXT_ON_LIGHT;
    }

    // WCAG 相对亮度：sRGB 分量线性化后按 0.2126 / 0.7152 / 0.0722 加权。
    private static double relativeLuminance(int argb) {
        double r = linearize((argb >> 16 & 0xFF) / 255.0);
        double g = linearize((argb >> 8 & 0xFF) / 255.0);
        double b = linearize((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linearize(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static double contrast(int textColor, double backgroundLuminance) {
        double textLuminance = relativeLuminance(textColor);
        double lighter = Math.max(textLuminance, backgroundLuminance);
        double darker = Math.min(textLuminance, backgroundLuminance);
        return (lighter + 0.05) / (darker + 0.05);
    }

    // 输入可能先于首帧渲染到达：没有字体就无法配置控件，此时保持未布局状态。
    private void layoutIfPossible() {
        if (font != null) layout(font);
    }
}
