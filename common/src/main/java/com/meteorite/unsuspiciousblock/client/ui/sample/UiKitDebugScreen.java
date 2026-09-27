package com.meteorite.unsuspiciousblock.client.ui.sample;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.kit.LightboxImage;
import com.meteorite.unsuspiciousblock.client.ui.kit.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiAction;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusManager;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiImageView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox;
import com.meteorite.unsuspiciousblock.client.ui.overlay.LightboxOverlay;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLinearLayout;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiMetrics;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTransform;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S1 临时人工验证页 + 阶段 A/B/C/D 新组件人工验证页；仅开发环境显式启用，验收完成后移除。
 *
 * <p>本类位于示例层（{@code client/ui/sample}）而不是 kit：它依赖宿主资源常量与非 kit 的语义色表，
 * 因此 kit 保持零项目依赖，脚本 {@code scripts/check-ui-kit-boundaries.ps1} 据此守住边界。</p>
 *
 * <p>画面左列是阶段 A/B/C 新组件的演示区（{@link UiScrollView} + {@link UiControlGroup} +
 * {@link UiLinearLayout} + {@link UiControlStyle} 状态样例 + 原生 {@code EditBox} 适配器），
 * 右列仍是既有的 200 行 {@link UiDocument} 演示；两者共用同一套外层面板 pose。</p>
 *
 * <p>键盘：P 切换外层 pose 1x/2x（同时重建布局），F 切换调试叠加层（默认关闭），
 * Tab / Shift+Tab 在演示区焦点目标间移动（顺序含原生输入框），Space / Enter 激活焦点控件。
 * 调试叠加层关闭时不绘制任何额外内容。</p>
 *
 * <p>阶段 D：演示区里还有一个按钮打开 {@link UiLightbox} 图片灯箱（内容视图是 {@link UiImageView}，
 * 页内图集放两张图）；灯箱按未缩放的真实屏幕坐标画在所有内容之上并独占输入，ESC 由灯箱消费。</p>
 *
 * <p>阶段 E：新增一个入口打开 {@link UiKitSampleScreen}——第三方视角的最小接入示例
 * （按钮 / 滚动 / 灯箱 / 焦点），关闭后回到本页。</p>
 */
public final class UiKitDebugScreen extends Screen {
    private static final String PREFIX = "screen.unsuspiciousblock.ui_kit_debug.";
    private static final String SAMPLE_PREFIX = "screen.unsuspiciousblock.ui_kit_sample.";
    private static final int BRANCH_COLOR = 0xFF896C48;

    // 演示区几何：固定停靠左侧，右侧留给既有 UiDocument 演示。
    private static final int PANEL_X = 8;
    private static final int PANEL_TOP = 30;
    private static final int PANEL_WIDTH = 172;
    private static final int ROW_SPACING = 4;
    private static final int CONTENT_PADDING = 4;
    private static final int ROW_HEIGHT = 18;
    private static final int SAMPLE_ROW_HEIGHT = 16;
    private static final int SAMPLE_ROW_FIRST = 5;
    private static final int SAMPLE_ROW_COUNT = 4;
    private static final int LIST_ROW_HEIGHT = 14;
    private static final int LIST_ROW_COUNT = 12;
    private static final int LIST_ROW_FIRST = SAMPLE_ROW_FIRST + SAMPLE_ROW_COUNT;

    // 调试叠加层配色；只在 F 开关打开时用到。
    private static final int DEBUG_PANEL_COLOR = 0xFFFFD050;
    private static final int DEBUG_CONTROL_COLOR = 0xFF80FF80;
    private static final int DEBUG_DISABLED_COLOR = 0xFFFF8040;
    private static final int DEBUG_LAYOUT_COLOR = 0xFF40C0FF;
    private static final int DEBUG_FOCUS_COLOR = 0xFFFF4040;


    // 状态样例：下标与 UiControlStyle 的绘制状态一一对应（普通/悬停/按下/选中/禁用/焦点）。
    private static final String[] SAMPLE_KEYS = {
            "demo_sample_normal", "demo_sample_hover", "demo_sample_pressed",
            "demo_sample_selected", "demo_sample_disabled", "demo_sample_focus"};

    private final Screen parent;
    private final UiTransform frameTransform = new UiTransform();
    private UiDocument document;
    private UiRect frameBounds;
    private boolean dragging;
    private int outerScale = 1;
    private int ticks;
    private Component instructions;
    private Component diagnostics = Component.empty();

    // ---------- 阶段 A/B/C 演示区状态 ----------
    private final UiScrollView demoScroll = new UiScrollView();
    private final UiControlGroup demoControls = new UiControlGroup();
    private final UiFocusManager demoFocus = new UiFocusManager();
    private final EditBoxFocusTarget editBoxTarget = new EditBoxFocusTarget();
    /** 调试叠加层需要遍历的全部演示控件（控件组控件 + 状态样例）。 */
    private final List<UiControl> demoControlList = new ArrayList<>();
    /** 线性布局实例（纵向行槽与横向子布局），调试叠加层据此画每个子项矩形。 */
    private final List<UiLinearLayout> demoLayouts = new ArrayList<>();
    private final List<UiControl> styleSamples = new ArrayList<>();
    private final List<UiControl> hoverSamples = new ArrayList<>();
    private final List<UiFocusTarget> demoFocusOrder = new ArrayList<>();
    private UiRect demoPanel = new UiRect(PANEL_X, PANEL_TOP, 0, 0);
    private int demoHeaderHeight;
    private EditBox demoEditBox;
    private String demoEditText = "";
    private boolean editBoxDragging;
    private boolean debugOverlay;
    private int activationCount;

    // ---------- 阶段 D 灯箱状态 ----------
    /** 用生产路径的模态适配器承载灯箱，调试页与业务页共用同一套遮罩/独占输入/层高实现。 */
    private final OverlayLayer lightboxLayer = new OverlayLayer();
    private UiLightbox lightbox;
    private UiImageView lightboxView;
    private LightboxImage[] lightboxImages;
    private int lightboxIndex;

    public UiKitDebugScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
        // 焦点变化时把目标滚进视口；只在键盘导航与显式 focusOn 时触发，不重建内容、不重新排版。
        demoFocus.setListener((previous, current) -> {
            if (current != null) demoScroll.ensureVisible(current.bounds());
        });
    }

    public static boolean enabled() {
        return Services.PLATFORM.isDevelopmentEnvironment() && Boolean.getBoolean("unsuspiciousblock.uiKitDebug");
    }

    @Override
    protected void init() {
        dragging = false;
        instructions = text("instructions");
        if (document == null) document = new UiDocument(TextMeasurer.of(font), true);
        // resize / 字体重载时重新测量；普通 render、拖动、缩放不经过这里。
        document.invalidateLayout();
        int usableWidth = Math.max(1, width / outerScale);
        // 左列让给新组件演示区，UiDocument 停靠在剩余空间里。
        int frameWidth = Math.clamp(usableWidth - PANEL_WIDTH - 24, 1, 152);
        int frameHeight = Math.clamp((height - 64) / outerScale - font.lineHeight - 7, 1, 166);
        int docHeight = frameHeight + font.lineHeight + 7;
        int x = Math.max(PANEL_WIDTH + 16, (usableWidth - frameWidth) / 2);
        if (x + frameWidth > usableWidth) x = Math.max(0, usableWidth - frameWidth);
        int y = Math.max(1, (height / outerScale - docHeight) / 2);
        document.setViewport(x, y, frameWidth, docHeight);
        frameBounds = new UiRect(x, y + font.lineHeight + 7, frameWidth, frameHeight);
        long revision = ((long) frameWidth << 32) | frameHeight;
        document.setContent(revision, () -> List.of(
                new UiNode.Row(0, UiNode.NO_PARENT, text("reset"), UiTextPalette.Parchment.TITLE, List.of(),
                        List.of(text("reset_tooltip")), null, frameTransform::reset),
                new UiNode.Gap(3),
                new UiNode.Frame(new UiNode.FrameSpec(frameWidth, frameHeight, BRANCH_COLOR, frameTransform),
                        buildRows())));
        document.layout();
        initDemoArea();
        refreshDiagnostics();
    }

    /** 25 个分组 × (1 根 + 3 子 + 2×2 孙) = 200 行，每 10 行带 3 个图标 = 60 个图标。 */
    private static List<UiNode> buildRows() {
        List<UiNode> nodes = new ArrayList<>(202);
        int label = 0;
        for (int section = 0; section < 25; section++) {
            int root = nodes.size();
            nodes.add(row(root, 0, UiNode.NO_PARENT, ++label, label % 10 == 0));
            for (int child = 0; child < 3; child++) {
                int childIndex = nodes.size();
                nodes.add(row(childIndex, 12, root, ++label, label % 10 == 0));
                if (child == 2) continue;
                for (int grandchild = 0; grandchild < 2; grandchild++) {
                    int index = nodes.size();
                    nodes.add(row(index, 24, childIndex, ++label, label % 10 == 0));
                }
            }
            if (section == 12) {
                nodes.add(new UiNode.Gap(4));
                nodes.add(new UiNode.Divider(BRANCH_COLOR));
            }
        }
        return nodes;
    }

    private static UiNode row(int index, int indent, int parent, int label, boolean withIcons) {
        List<UiNode.InlineIcon> icons = List.of();
        if (withIcons) {
            ResourceLocation atlas = ResourceLocation.fromNamespaceAndPath(
                    Constants.MOD_ID, "textures/gui/toolbar_icons.png");
            icons = List.of(
                    new UiNode.InlineIcon(new UiIcon.Item(new ItemStack(Items.BRUSH)),
                            List.of(Items.BRUSH.getDescription()), "brush", null),
                    new UiNode.InlineIcon(new UiIcon.Item(new ItemStack(Items.DIAMOND)),
                            List.of(Items.DIAMOND.getDescription()), "diamond", null),
                    new UiNode.InlineIcon(new UiIcon.Sprite(atlas, 0, 0, 9, 9, 81, 27),
                            List.of(text("sprite")), "sprite", null));
        }
        return new UiNode.Row(indent, parent, text("row", label), UiTextPalette.Parchment.BODY,
                icons, List.of(text("row_tooltip", label)), index, null);
    }

    // ---------- 演示区构建 ----------

    // 重建演示区：内容坐标原点在视口左上角，因此布局几何只依赖面板尺寸，不依赖面板在屏幕上的位置。
    private void initDemoArea() {
        if (demoEditBox != null) demoEditText = demoEditBox.getValue();
        int usableWidth = Math.max(1, width / outerScale);
        int panelWidth = Math.clamp(usableWidth - PANEL_X - 8, 48, PANEL_WIDTH);
        demoHeaderHeight = font.lineHeight + 2;
        int panelHeight = Math.max(demoHeaderHeight + 24, height / outerScale - PANEL_TOP - 16);
        demoPanel = new UiRect(PANEL_X, PANEL_TOP, panelWidth, panelHeight);
        demoScroll.setViewport(demoPanel.x(), demoPanel.y() + demoHeaderHeight, panelWidth,
                Math.max(1, panelHeight - demoHeaderHeight));
        demoScroll.setScrollbarVisible(true);
        int contentWidth = Math.max(16, panelWidth - UiScrollView.SCROLLBAR_WIDTH);
        demoControls.setStyle(UiControlStyle.PARCHMENT);

        demoLayouts.clear();
        demoControlList.clear();
        styleSamples.clear();
        hoverSamples.clear();
        demoFocusOrder.clear();
        demoControls.beginUpdate();

        List<UiLinearLayout.Child> slots = new ArrayList<>();
        for (int i = 0; i < SAMPLE_ROW_FIRST; i++) slots.add(UiLinearLayout.Child.content(ROW_HEIGHT));
        for (int i = 0; i < SAMPLE_ROW_COUNT; i++) slots.add(UiLinearLayout.Child.content(SAMPLE_ROW_HEIGHT));
        for (int i = 0; i < LIST_ROW_COUNT; i++) slots.add(UiLinearLayout.Child.content(LIST_ROW_HEIGHT));
        UiLinearLayout rows = verticalLayout(contentWidth, slots);

        // 行 0：主轴 FIXED + REMAIN。
        UiLinearLayout row0 = horizontalLayout(rows.bounds(0), List.of(
                UiLinearLayout.Child.fixed(44), UiLinearLayout.Child.remain()));
        addDemoControl("row0.fixed", row0.bounds(0), text("demo_button_fixed"), true);
        addDemoControl("row0.remain", row0.bounds(1), text("demo_button_remain"), true);

        // 行 1：CONTENT + REMAIN(min 24, max 90) + withLeading 的纯标签列。
        UiLinearLayout row1 = horizontalLayout(rows.bounds(1), List.of(
                UiLinearLayout.Child.content(56),
                UiLinearLayout.Child.remain(24, 90),
                UiLinearLayout.Child.fixed(30).withLeading(6)));
        addDemoControl("row1.content", row1.bounds(0), text("demo_button_content"), true);
        addDemoControl("row1.remain", row1.bounds(1), text("demo_button_remain_range"), true);
        addLabelControl("row1.label", row1.bounds(2), text("demo_label_static"));

        // 行 2：可用按钮 + 禁用按钮（禁用控件仍在层序里，但不命中、不激活、不进焦点序列）。
        UiLinearLayout row2 = horizontalLayout(rows.bounds(2), List.of(
                UiLinearLayout.Child.fixed(48), UiLinearLayout.Child.remain()));
        addDemoControl("row2.enabled", row2.bounds(0), text("demo_button_enabled"), true);
        addDemoControl("row2.disabled", row2.bounds(1), text("demo_button_disabled"), false);

        // 行 3：原生 EditBox 适配器，Tab 会经过它再回到 kit 控件。
        UiLinearLayout row3 = horizontalLayout(rows.bounds(3), List.of(
                UiLinearLayout.Child.fixed(44), UiLinearLayout.Child.remain()));
        addLabelControl("row3.label", row3.bounds(0), text("demo_edit_label"));
        UiRect box = row3.bounds(1);
        demoEditBox = new EditBox(font, box.x(), box.y(), box.width(), box.height(), text("demo_edit_label"));
        demoEditBox.setHint(text("demo_edit_hint"));
        demoEditBox.setMaxLength(32);
        demoEditBox.setValue(demoEditText);

        // 行 4：阶段 D 灯箱入口 + 阶段 E 最小示例页入口（两个半宽按钮）。
        UiLinearLayout row4 = horizontalLayout(rows.bounds(4), List.of(
                UiLinearLayout.Child.remain(), UiLinearLayout.Child.remain()));
        addDemoControl("open_lightbox", row4.bounds(0), text("demo_lightbox"), true,
                List.of(text("lightbox_button_tooltip")), this::openLightbox);
        addDemoControl("open_sample", row4.bounds(1), sampleText("open"), true,
                List.of(sampleText("open_tooltip")), this::openSampleScreen);

        // 行 5~8：PARCHMENT / DARK 各一组状态样例。
        buildStyleSamples(rows.bounds(SAMPLE_ROW_FIRST), UiControlStyle.PARCHMENT,
                UiTextPalette.Dark.BODY, "demo_style_parchment", 0);
        buildStyleSamples(rows.bounds(SAMPLE_ROW_FIRST + 1), UiControlStyle.PARCHMENT,
                UiTextPalette.Dark.BODY, "demo_style_parchment", 3);
        buildStyleSamples(rows.bounds(SAMPLE_ROW_FIRST + 2), UiControlStyle.DARK,
                UiTextPalette.Parchment.BODY, "demo_style_dark", 0);
        buildStyleSamples(rows.bounds(SAMPLE_ROW_FIRST + 3), UiControlStyle.DARK,
                UiTextPalette.Parchment.BODY, "demo_style_dark", 3);

        // 行 9 起：纯标签列表，把内容撑高以便验证滚动偏移与滚动条。
        for (int i = 0; i < LIST_ROW_COUNT; i++) {
            addLabelControl("list." + i, rows.bounds(LIST_ROW_FIRST + i), text("demo_list_row", i + 1));
        }

        demoControls.endUpdate();
        // 内容高度直接取自布局对象，不另行累加尺寸。
        demoScroll.setContentHeight(rows.usedMain());

        // 焦点按视觉顺序登记：行 0/1/2 的可聚焦控件 → 原生输入框（禁用与纯标签自动跳过）。
        demoControls.collectFocusTargets(demoFocusOrder);
        demoFocusOrder.add(editBoxTarget);
        demoFocus.beginUpdate();
        for (UiFocusTarget target : demoFocusOrder) demoFocus.add(target);
        demoFocus.endUpdate();
        if (demoFocus.focused() == editBoxTarget) demoEditBox.setFocused(true);
    }

    // 纵向行槽容器：高度交给布局自己算，内容高度由 usedMain() 上报。
    private UiLinearLayout verticalLayout(int width, List<UiLinearLayout.Child> children) {
        UiLinearLayout layout = new UiLinearLayout(UiLinearLayout.Axis.VERTICAL, ROW_SPACING, CONTENT_PADDING);
        layout.setChildren(children);
        layout.setBounds(0, 0, width, 0);
        demoLayouts.add(layout);
        return layout;
    }

    // 横向子布局：演示一行里宽度规则不同的列，也是「纵向行槽套横向行」的预期用法。
    private UiLinearLayout horizontalLayout(UiRect row, List<UiLinearLayout.Child> children) {
        UiLinearLayout layout = new UiLinearLayout(UiLinearLayout.Axis.HORIZONTAL, ROW_SPACING, 0);
        layout.setChildren(children);
        layout.setBounds(row.x(), row.y(), row.width(), row.height());
        demoLayouts.add(layout);
        return layout;
    }

    // 样式状态样例：每个控件只负责一种状态，悬停态用合成的鼠标位置制造，读数与状态均不依赖真实鼠标。
    private void buildStyleSamples(UiRect row, UiControlStyle style, int textColor, String styleKey, int firstState) {
        UiLinearLayout layout = horizontalLayout(row, List.of(
                UiLinearLayout.Child.remain(), UiLinearLayout.Child.remain(), UiLinearLayout.Child.remain()));
        for (int i = 0; i < 3; i++) {
            int state = firstState + i;
            UiRect rect = layout.bounds(i);
            UiControl sample = new UiControl();
            sample.setStyle(style);
            sample.setBounds(rect.x(), rect.y(), rect.width(), rect.height());
            sample.configure(font, text(SAMPLE_KEYS[state]), textColor, null,
                    List.of(text("demo_sample_tooltip", text(styleKey), text(SAMPLE_KEYS[state]))),
                    this::onDemoActivated);
            sample.setSelected(state == 3);
            sample.setPressed(state == 2);
            sample.setFocused(state == 5);
            sample.setEnabled(state != 4);
            if (state == 1) hoverSamples.add(sample);
            styleSamples.add(sample);
            demoControlList.add(sample);
        }
    }

    // 从控件组按稳定 key 取控件并配置成可激活按钮；默认动作只累加调试计数。
    private void addDemoControl(Object key, UiRect bounds, Component label, boolean enabled) {
        addDemoControl(key, bounds, label, enabled, List.of(text("demo_button_tooltip")), this::onDemoActivated);
    }

    // 带自定义提示与动作的重载（例如打开灯箱的入口按钮）。
    private void addDemoControl(Object key, UiRect bounds, Component label, boolean enabled,
                                List<Component> tooltip, UiAction action) {
        UiControl control = demoControls.obtain(key);
        control.configure(font, label, UiTextPalette.Dark.BODY, null, tooltip, action);
        control.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        control.setEnabled(enabled);
        demoControlList.add(control);
    }

    // 纯标签：可命中、可显示 tooltip，但没有 action，因此不进 Tab 焦点序列。
    private void addLabelControl(Object key, UiRect bounds, Component label) {
        UiControl control = demoControls.obtain(key);
        control.configure(font, label, UiTextPalette.Dark.BODY, null, List.of(), null);
        control.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        control.setEnabled(true);
        demoControlList.add(control);
    }

    // 演示按钮的动作只累加计数：把「激活确实发生」暴露给调试叠加层，不产生业务副作用。
    private void onDemoActivated() {
        activationCount++;
    }

    // ---------- 阶段 E 最小示例页 ----------

    // 打开最小示例页（第三方视角，只依赖 kit 公开入口与模态适配层）；关闭后回到本页。
    private void openSampleScreen() {
        Objects.requireNonNull(minecraft).setScreen(new UiKitSampleScreen(this));
    }

    // 示例页文案复用 ui_kit_sample.* 前缀。
    private static Component sampleText(String key) {
        return Component.translatable(SAMPLE_PREFIX + key);
    }

    // ---------- 阶段 D 灯箱 ----------

    // 打开灯箱：初始内容是图集第一张，图集与文案都交给外壳；页面只保留引用与下标。
    private void openLightbox() {
        if (lightbox != null) return;
        // 打开时结束可能正在进行的 Frame 拖动，并收掉下层焦点：
        // 灯箱独占输入期间，原生输入框必须失焦，否则字符还会悄悄打进后台字段。
        dragging = false;
        demoFocus.clearFocus();
        lightboxImages = buildLightboxImages();
        lightboxIndex = 0;
        lightboxView = new UiImageView(lightboxImages[0], missingText());
        UiLightbox box = new UiLightbox(lightboxLabels(), lightboxView, this::closeLightbox);
        box.setGallery(new DebugGallery());
        box.setText(lightboxImages[0].title(), lightboxImages[0].description());
        lightbox = box;
        lightboxLayer.setBounds(width, height);
        lightboxLayer.open(new LightboxOverlay(lightboxLayer, box));
        refreshDiagnostics();
    }

    // 关闭灯箱：模态层会回调 LightboxOverlay.closed()（外壳在那里释放焦点与按压捕获），页面只清引用。
    private void closeLightbox() {
        lightboxLayer.close();
        lightbox = null;
        lightboxView = null;
        refreshDiagnostics();
    }

    // 图集切换：换内容（新视图重新求 fit）并同步标题与描述；外壳只负责显示与请求切换。
    private void applyLightboxImage() {
        UiLightbox box = lightbox;
        if (box == null || lightboxImages == null || lightboxImages.length == 0) return;
        LightboxImage image = lightboxImages[lightboxIndex];
        lightboxView = new UiImageView(image, missingText());
        box.setContent(lightboxView);
        box.setText(image.title(), image.description());
        refreshDiagnostics();
    }

    // 缺图占位沿用阶段 D 已就位的通用灯箱键，不再重复新增。
    private static Component missingText() {
        return Component.translatable("screen.unsuspiciousblock.lightbox.missing");
    }

    // 两张图的图集：pottery_wheel_gui.png（256×256 整图）与 catalog_entry.png（152×76 整图）。
    private static LightboxImage[] buildLightboxImages() {
        ResourceLocation potteryWheel = ResourceLocation.fromNamespaceAndPath(
                Constants.MOD_ID, "textures/gui/pottery_wheel_gui.png");
        ResourceLocation catalogEntry = ResourceLocation.fromNamespaceAndPath(
                Constants.MOD_ID, "textures/gui/catalog_entry.png");
        return new LightboxImage[]{
                LightboxImage.whole(
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "debug/lightbox_pottery_wheel"),
                        potteryWheel, 256, 256,
                        text("lightbox_image_pottery_title"), text("lightbox_image_pottery_desc")),
                LightboxImage.whole(
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "debug/lightbox_catalog_entry"),
                        catalogEntry, 152, 76,
                        text("lightbox_image_catalog_title"), text("lightbox_image_catalog_desc"))};
    }

    // 外壳文案复用阶段 D 的通用灯箱键（kit 不持有文案 key）。
    private static UiLightbox.Labels lightboxLabels() {
        return new UiLightbox.Labels() {
            @Override public Component close() { return lightboxText("close"); }
            @Override public Component zoomIn() { return lightboxText("zoom_in"); }
            @Override public Component zoomOut() { return lightboxText("zoom_out"); }
            @Override public Component fit() { return lightboxText("fit"); }
            @Override public Component previous() { return lightboxText("previous"); }
            @Override public Component next() { return lightboxText("next"); }
            @Override public Component position(int index, int total) {
                return Component.translatable("screen.unsuspiciousblock.lightbox.position", index, total);
            }
        };
    }

    private static Component lightboxText(String key) {
        return Component.translatable("screen.unsuspiciousblock.lightbox." + key);
    }

    /** 页内图集：下标由页面持有，导航时换内容；外壳只请求切换、不关心图从哪来。 */
    private final class DebugGallery implements UiLightbox.Gallery {
        @Override public int index() { return lightboxIndex; }
        @Override public int total() { return lightboxImages == null ? 0 : lightboxImages.length; }
        @Override public void navigate(int delta) {
            if (lightboxImages == null || lightboxImages.length == 0) return;
            lightboxIndex = Math.floorMod(lightboxIndex + delta, lightboxImages.length);
            applyLightboxImage();
        }
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xEE181818);
        graphics.drawString(font, title, 8, 6, UiTextPalette.Dark.TITLE, false);
        graphics.drawString(font, instructions, 8, 18, UiTextPalette.Dark.BODY, false);
        graphics.drawString(font, diagnostics, 8, height - 12, UiTextPalette.Dark.BODY, false);
        double localX = mouseX / (double) outerScale;
        double localY = mouseY / (double) outerScale;
        graphics.pose().pushPose();
        graphics.pose().scale(outerScale, outerScale, 1);
        try {
            UiRect bounds = document.viewport();
            graphics.fill(bounds.x() - 1, bounds.y() - 1, bounds.right() + 1, bounds.bottom() + 1, 0xFF896C48);
            graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xFFF2E5C6);
            graphics.fill(frameBounds.x() - 1, frameBounds.y() - 1,
                    frameBounds.right() + 1, frameBounds.bottom() + 1, 0xFF896C48);
            graphics.fill(frameBounds.x(), frameBounds.y(), frameBounds.right(), frameBounds.bottom(), 0xFFF2E5C6);
            document.render(graphics, font);
            renderDemoArea(graphics, localX, localY, partialTick);
        } finally {
            graphics.pose().popPose();
        }
        if (lightboxLayer.isOpen()) {
            // 模态层负责 flush、层高与遮罩；坐标用未缩放的真实屏幕坐标。
            lightboxLayer.setBounds(width, height);
            lightboxLayer.render(graphics, font, mouseX, mouseY, partialTick);
            // 模态期间页面不再画自己的 tooltip，避免与灯箱的提示叠加。
            return;
        }
        if (demoScroll.contains(localX, localY)) {
            // 演示区同一位置只能有一个 tooltip：只画最上层命中且提示非空的目标。
            UiTarget target = demoControls.targetAt(demoScroll.toContentX(localX), demoScroll.toContentY(localY));
            if (target != null && !target.tooltip().isEmpty()) {
                graphics.renderComponentTooltip(font, target.tooltip(), mouseX, mouseY);
            }
            return;
        }
        UiTarget target = document.hit(localX, localY);
        if (target != null && !target.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, target.tooltip(), mouseX, mouseY);
        }
    }

    // 演示区：面板底 → 内容（滚动裁剪内）→ 滚动条 → 调试叠加层（默认关闭时不画任何东西）。
    private void renderDemoArea(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        graphics.fill(demoPanel.x() - 1, demoPanel.y() - 1,
                demoPanel.right() + 1, demoPanel.bottom() + 1, BRANCH_COLOR);
        graphics.fill(demoPanel.x(), demoPanel.y(), demoPanel.right(), demoPanel.bottom(), 0xFF202020);
        graphics.fill(demoPanel.x(), demoPanel.y(), demoPanel.right(),
                demoPanel.y() + demoHeaderHeight, 0xFF3A2F1E);
        graphics.drawString(font, text("demo_title"), demoPanel.x() + 2, demoPanel.y() + 1,
                UiTextPalette.Dark.TITLE, false);
        boolean inside = demoScroll.contains(mouseX, mouseY);
        int hoverX = inside ? (int) demoScroll.toContentX(mouseX) : -1;
        int hoverY = inside ? (int) demoScroll.toContentY(mouseY) : -1;
        demoScroll.push(graphics);
        try {
            demoControls.render(graphics, font, hoverX, hoverY);
            for (UiControl sample : styleSamples) {
                UiRect rect = sample.bounds();
                boolean forced = hoverSamples.contains(sample);
                sample.render(graphics, font,
                        forced ? rect.x() + rect.width() / 2 : -1,
                        forced ? rect.y() + rect.height() / 2 : -1);
            }
            if (demoEditBox != null) demoEditBox.render(graphics, hoverX, hoverY, partialTick);
            if (debugOverlay) renderDebugContent(graphics);
        } finally {
            demoScroll.pop(graphics);
        }
        demoScroll.renderScrollbar(graphics, UiControlStyle.PARCHMENT);
        if (debugOverlay) renderDebugPanel(graphics);
    }

    // 面板级调试读数：视口矩形与滚动几何，数值全部读自已排好版的对象，不重新计算布局。
    private void renderDebugPanel(GuiGraphics graphics) {
        UiRect viewport = demoScroll.viewport();
        outline(graphics, demoPanel, DEBUG_PANEL_COLOR);
        outline(graphics, viewport, DEBUG_PANEL_COLOR);
        graphics.drawString(font, text("debug_viewport", demoScroll.offset(), demoScroll.maxOffset(),
                        demoScroll.contentHeight()),
                viewport.x() + 2, viewport.bottom() - 2 * font.lineHeight - 2, DEBUG_PANEL_COLOR, false);
        graphics.drawString(font, text("debug_activations", activationCount),
                viewport.x() + 2, viewport.bottom() - font.lineHeight - 1, DEBUG_CONTROL_COLOR, false);
    }

    // 内容级调试叠加层：布局子项矩形、控件矩形与 accessibleName、当前焦点目标的强调边框。
    private void renderDebugContent(GuiGraphics graphics) {
        for (UiLinearLayout layout : demoLayouts) {
            for (int i = 0; i < layout.size(); i++) outline(graphics, layout.bounds(i), DEBUG_LAYOUT_COLOR);
        }
        for (UiControl control : demoControlList) {
            UiRect rect = control.bounds();
            int color = control.isEnabled() ? DEBUG_CONTROL_COLOR : DEBUG_DISABLED_COLOR;
            outline(graphics, rect, color);
            Component name = control.accessibleName();
            if (name != null) graphics.drawString(font, name, rect.x() + 1, rect.y() + 1, color, false);
        }
        if (demoEditBox != null) {
            UiRect rect = editBoxTarget.bounds();
            outline(graphics, rect, DEBUG_CONTROL_COLOR);
            graphics.drawString(font, text("demo_edit_label"), rect.x() + 1,
                    rect.y() - font.lineHeight - 1, DEBUG_CONTROL_COLOR, false);
        }
        // 控件自带焦点轮廓；这里只在叠加层里再套一层强调边框，便于一眼定位当前焦点目标。
        UiFocusTarget focused = demoFocus.focused();
        if (focused != null) {
            UiRect rect = focused.bounds();
            outline(graphics, new UiRect(rect.x() - 1, rect.y() - 1, rect.width() + 2, rect.height() + 2),
                    DEBUG_FOCUS_COLOR);
            Component name = focused.accessibleName();
            if (name != null) {
                graphics.drawString(font, name, rect.x() + 1, rect.bottom() + 1, DEBUG_FOCUS_COLOR, false);
            }
        }
    }

    // 1 像素矩形边框；退化矩形（宽或高为 0）不绘制。
    private static void outline(GuiGraphics graphics, UiRect rect, int color) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, color);
        graphics.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), color);
        graphics.fill(rect.x(), rect.y() + 1, rect.x() + 1, rect.bottom() - 1, color);
        graphics.fill(rect.right() - 1, rect.y() + 1, rect.right(), rect.bottom() - 1, color);
    }

    // ---------- 输入 ----------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (lightboxLayer.mouseClicked(mouseX, mouseY, button)) return true;
        double x = mouseX / outerScale;
        double y = mouseY / outerScale;
        if (button == 0) {
            if (demoScroll.contains(x, y)) {
                double contentX = demoScroll.toContentX(x);
                double contentY = demoScroll.toContentY(y);
                // 演示区优先：滚动条 → 原生输入框 → 控件组 → 空白处清除焦点。
                if (demoScroll.hitScrollbar(x, y)) return demoScroll.mousePressed(x, y, button);
                if (demoEditBox != null && demoEditBox.mouseClicked(contentX, contentY, button)) {
                    editBoxDragging = true;
                    demoFocus.focusOn(editBoxTarget);
                    return true;
                }
                // 鼠标点击不夺取焦点：点控件或空白都立即收掉键盘焦点，
                // 否则轮廓会留在被点过的按钮上，关闭模态后仍然可见。
                demoFocus.clearFocus();
                if (demoControls.mousePressed(contentX, contentY, button)) return true;
                return true;
            }
            // 鼠标点击不夺取焦点：落在 UiDocument 树上时同样收掉键盘焦点，避免轮廓留在上一次 Tab 的控件上。
            demoFocus.clearFocus();
            UiTarget target = document.hit(x, y);
            if (target != null && target.action() != null) {
                target.action().run();
                return true;
            }
            if (frameBounds.contains(x, y)) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (lightboxLayer.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        double x = mouseX / outerScale;
        double y = mouseY / outerScale;
        if (button == 0) {
            if (demoScroll.mouseDragged(y)) return true;
            if (editBoxDragging && demoEditBox != null
                    && demoEditBox.mouseDragged(x, y, button, dragX / outerScale, dragY / outerScale)) {
                return true;
            }
            if (dragging) {
                frameTransform.panBy(dragX / outerScale, dragY / outerScale);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (lightboxLayer.mouseReleased(mouseX, mouseY, button)) return true;
        if (button == 0) {
            demoScroll.mouseReleased();
            if (editBoxDragging) {
                editBoxDragging = false;
                if (demoEditBox != null) {
                    demoEditBox.mouseReleased(mouseX / outerScale, mouseY / outerScale, button);
                }
            }
            if (dragging) {
                dragging = false;
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (lightboxLayer.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        double x = mouseX / outerScale;
        double y = mouseY / outerScale;
        if (demoScroll.contains(x, y) && demoScroll.scrollBy(scrollY)) return true;
        if (frameBounds.contains(x, y)) {
            // Frame 变换的原点位于父文档内容坐标中。
            frameTransform.zoomBy((int) Math.signum(scrollY), document.transform().toLocalX(x),
                    document.transform().toLocalY(y));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 由灯箱消费（关灯箱而不是关整个调试页）；其余按键也被模态吞掉。
        if (lightboxLayer.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (keyCode == GLFW.GLFW_KEY_P) {
            outerScale = outerScale == 1 ? 2 : 1;
            init();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F) {
            // 调试叠加层默认关闭；打开前不绘制任何额外内容。
            debugOverlay = !debugOverlay;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && demoFocus.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (demoEditBox != null && demoEditBox.isFocused()
                && demoEditBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (demoFocus.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (lightboxLayer.charTyped(codePoint, modifiers)) return true;
        if (demoEditBox != null && demoEditBox.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0) refreshDiagnostics();
        if (ticks % 100 == 0) {
            UiMetrics metrics = document.metrics();
            Constants.LOG.info("UI kit: build={}ns layout={}ns render={}ns hit={}ns builds={} layouts={}",
                    metrics.buildNanos(), metrics.layoutNanos(), metrics.renderNanos(), metrics.hitNanos(),
                    metrics.builds(), metrics.layouts());
        }
    }

    private void refreshDiagnostics() {
        UiMetrics metrics = document.metrics();
        // 追加的调试读数直接取自对象（焦点管理器 / 滚动视口 / 控件组 / 灯箱内容），不重新计算布局。
        MutableComponent line = Component.empty()
                .append(text("metrics", metrics.builds(), metrics.layouts(), metrics.buildNanos() / 1000,
                        metrics.layoutNanos() / 1000, metrics.renderNanos() / 1000, metrics.hitNanos() / 1000,
                        (int) (frameTransform.scale() * 100), outerScale))
                .append(" ")
                .append(text("debug_readout", demoFocus.focusedIndex(), demoFocus.size(),
                        demoScroll.offset(), demoScroll.maxOffset(), demoScroll.contentHeight(),
                        demoControls.size()));
        if (lightbox != null && lightboxView != null && lightboxImages != null) {
            // 灯箱读数：图集下标与内容视图自己上报的缩放百分比。
            line.append(" ").append(text("lightbox_readout", lightboxIndex + 1, lightboxImages.length,
                    lightboxView.zoomPercent()));
        }
        diagnostics = line;
    }

    @Override
    public void onClose() { Objects.requireNonNull(minecraft).setScreen(parent); }

    /** 原生 EditBox 的焦点适配器：只代理焦点与边界，文字编辑、剪贴板与输入法仍归原版控件。 */
    private final class EditBoxFocusTarget implements UiFocusTarget {
        @Override
        public boolean canFocus() {
            return demoEditBox != null && demoEditBox.active && demoEditBox.visible;
        }

        @Override
        public void setFocused(boolean focused) {
            if (demoEditBox != null) demoEditBox.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return demoEditBox != null && demoEditBox.isFocused();
        }

        @Override
        public boolean activate() {
            // Enter / Space 的编辑语义属于原版输入框，适配器不消费。
            return false;
        }

        @Override
        public UiRect bounds() {
            if (demoEditBox == null) return new UiRect(0, 0, 0, 0);
            return new UiRect(demoEditBox.getX(), demoEditBox.getY(),
                    demoEditBox.getWidth(), demoEditBox.getHeight());
        }

        @Override
        public Component accessibleName() {
            return text("demo_edit_label");
        }
    }

    private static Component text(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
}
