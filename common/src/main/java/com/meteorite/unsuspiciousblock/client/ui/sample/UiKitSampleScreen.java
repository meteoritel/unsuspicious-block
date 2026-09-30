package com.meteorite.unsuspiciousblock.client.ui.sample;

import com.meteorite.unsuspiciousblock.client.ui.kit.LightboxImage;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiAction;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlGroup;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusManager;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiImageView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLinearLayout;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.overlay.LightboxOverlay;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 最小非笔记 Screen 示例：第三方视角下只用 {@code client/ui/kit} 的公开入口完成「按钮 + 滚动 + 灯箱 + 焦点」。
 *
 * <p>它刻意**不引用**宿主的状态类、{@code support} 包、布局/面板包与资源常量：只有 kit 公开入口、
 * 模态适配层（{@link OverlayLayer} + {@link LightboxOverlay}）与 Minecraft 客户端类型。贴图路径与
 * 语义色都自带在本类里，因此这份代码可以直接抄到另一个界面而不带入任何业务依赖。</p>
 *
 * <p>四个演示点：{@link UiControlGroup} + {@link UiControlStyle}（普通/禁用按钮）、{@link UiScrollView}
 * 视口内滚动的 {@link UiControlGroup}、{@link UiLightbox} + {@link UiImageView} 图片灯箱、
 * {@link UiFocusManager} 的 Tab/Shift+Tab 与 Space/Enter 激活（{@link UiLinearLayout} 负责摆放）。</p>
 *
 * <p>键盘：Tab / Shift+Tab 移焦点（焦点目标会被滚进视口），Space / Enter 激活；灯箱打开时 ESC 由灯箱消费；
 * 其余情况 ESC 返回上一屏。鼠标：按钮点击、视口滚轮与滚动条拖动；灯箱打开时全部输入先进模态层。</p>
 */
public final class UiKitSampleScreen extends Screen {
    private static final String PREFIX = "screen.unsuspiciousblock.ui_kit_sample.";
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_SPACING = 4;
    /** 行数刻意多于一屏：内容高度高于视口时滚动偏移与滚动条才有可验证的读数。 */
    private static final int LIST_ROWS = 16;
    private static final int TEXT_COLOR = 0xFFE8E8E8;
    private static final int MUTED_COLOR = 0xFFA0A0A0;
    private static final int PAGE_COLOR = 0xF0101010;
    private static final int PANEL_COLOR = 0xFF181818;
    private static final int BORDER_COLOR = 0xFF505050;
    // 示例自带的贴图与稳定 ID：不引用宿主资源常量，保持第三方视角。
    private static final ResourceLocation SAMPLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "textures/gui/catalog_entry.png");
    private static final ResourceLocation SAMPLE_IMAGE_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "sample/lightbox_image");
    private static final int SAMPLE_IMAGE_WIDTH = 152;
    private static final int SAMPLE_IMAGE_HEIGHT = 76;

    private final Screen parent;
    private final OverlayLayer overlays = new OverlayLayer();
    private final UiControlGroup buttons = new UiControlGroup();
    private final UiControlGroup scrollRows = new UiControlGroup();
    private final UiScrollView scroll = new UiScrollView();
    private final UiFocusManager focus = new UiFocusManager();
    /** 灯箱入口按钮：闭合后作为返回焦点（只有它当前确实持有焦点时才会被登记）。 */
    @Nullable private UiControl lightboxButton;
    private final List<UiFocusTarget> focusOrder = new ArrayList<>();
    private UiRect scrollViewport = new UiRect(0, 0, 0, 0);
    private Component status = Component.empty();
    private int activations;
    private int ticks;

    public UiKitSampleScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
        // 「把焦点目标滚进视口」是宿主自己的策略：kit 只通知焦点变化，不替宿主做滚动决定。
        focus.setListener((previous, current) -> {
            if (current != null) scroll.ensureVisible(current.bounds());
        });
    }

    // ---------- 布局 ----------

    @Override
    protected void init() {
        buttons.setStyle(UiControlStyle.DARK);
        scrollRows.setStyle(UiControlStyle.DARK);
        scroll.setScrollbarVisible(true);
        // 一格滚轮 = 一行：读数与视觉步长一致，便于人工核对。
        scroll.setStep(ROW_HEIGHT + ROW_SPACING);
        buildLayout();
        // 焦点按视觉顺序登记：按钮行在前，滚动内容行在后（禁用控件与纯标签自动跳过）。
        focus.beginUpdate();
        for (UiFocusTarget target : focusOrder) focus.add(target);
        focus.endUpdate();
        refreshStatus();
    }

    // 重建全部矩形：坐标一律为宿主 GUI 逻辑坐标，滚动内容用内容坐标（原点在视口左上角）。
    private void buildLayout() {
        focusOrder.clear();
        buttons.beginUpdate();
        scrollRows.beginUpdate();

        int contentWidth = Math.max(80, width - MARGIN * 2);
        int headerY = MARGIN + (font.lineHeight + 2) * 2 + 4;

        // 按钮行：UiLinearLayout 摆「占剩余的主按钮 + 两个固定宽按钮」。
        UiLinearLayout header = new UiLinearLayout(UiLinearLayout.Axis.HORIZONTAL, ROW_SPACING, 0);
        header.setChildren(List.of(
                UiLinearLayout.Child.remain(60, 200),
                UiLinearLayout.Child.fixed(72),
                UiLinearLayout.Child.fixed(72)));
        header.setBounds(MARGIN, headerY, contentWidth, ROW_HEIGHT);
        addControl(buttons, "primary", header.bounds(0), text("button_primary"), true, this::onActivated);
        addControl(buttons, "disabled", header.bounds(1), text("button_disabled"), false, this::onActivated);
        // 记下入口控件：它作为灯箱的「返回焦点」，键盘路径（Tab 到它、Space 打开）关闭后会拿回焦点。
        lightboxButton = addControl(buttons, "lightbox", header.bounds(2), text("button_lightbox"),
                true, this::openLightbox);

        // 滚动区：视口在按钮行下方，底部给状态行让位。
        int viewportTop = headerY + ROW_HEIGHT + 8;
        int viewportBottom = Math.max(viewportTop + 32, height - font.lineHeight - 10);
        scrollViewport = new UiRect(MARGIN, viewportTop, contentWidth, viewportBottom - viewportTop);
        scroll.setViewport(scrollViewport.x(), scrollViewport.y(), scrollViewport.width(), scrollViewport.height());
        int innerWidth = Math.max(40, contentWidth - UiScrollView.SCROLLBAR_WIDTH);

        // 滚动内容：纵向行槽（一行标题 + LIST_ROWS 行按钮），总高由 usedMain() 上报给视口。
        List<UiLinearLayout.Child> slots = new ArrayList<>();
        slots.add(UiLinearLayout.Child.content(font.lineHeight + 2));
        for (int i = 0; i < LIST_ROWS; i++) slots.add(UiLinearLayout.Child.content(ROW_HEIGHT));
        UiLinearLayout content = new UiLinearLayout(UiLinearLayout.Axis.VERTICAL, ROW_SPACING, 4);
        content.setChildren(slots);
        content.setBounds(0, 0, innerWidth, 0);

        // 标题行是纯标签：可命中但不带动作，因此不会占满 Tab 序列。
        UiControl caption = scrollRows.obtain("scroll.header");
        UiRect captionRect = content.bounds(0);
        caption.configure(font, text("scroll_header", LIST_ROWS), MUTED_COLOR, null, List.of(), null);
        caption.setBounds(captionRect.x(), captionRect.y(), captionRect.width(), captionRect.height());
        for (int i = 0; i < LIST_ROWS; i++) {
            addControl(scrollRows, "scroll.row." + i, content.bounds(i + 1), text("scroll_row", i + 1),
                    true, this::onActivated);
        }

        buttons.endUpdate();
        scrollRows.endUpdate();
        scroll.setContentHeight(content.usedMain());
        buttons.collectFocusTargets(focusOrder);
        scrollRows.collectFocusTargets(focusOrder);
    }

    // 从控件组取控件并配置；action 为 null 时是纯标签（可命中，但不进焦点序列）。
    private UiControl addControl(UiControlGroup group, Object key, UiRect bounds, Component label,
                                 boolean enabled, UiAction action) {
        UiControl control = group.obtain(key);
        control.configure(font, label, TEXT_COLOR, null, List.of(text("button_tooltip")), action);
        control.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        control.setEnabled(enabled);
        return control;
    }

    // 示例动作只累加计数并刷新读数：示例不含任何业务语义。
    private void onActivated() {
        activations++;
        refreshStatus();
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, PAGE_COLOR);
        graphics.drawString(font, title, MARGIN, MARGIN, TEXT_COLOR, false);
        graphics.drawString(font, text("hint"), MARGIN, MARGIN + font.lineHeight + 2, MUTED_COLOR, false);
        graphics.drawString(font, status, MARGIN, height - font.lineHeight - 4, MUTED_COLOR, false);
        int ruleY = MARGIN + (font.lineHeight + 2) * 2 + 1;
        graphics.fill(MARGIN, ruleY, width - MARGIN, ruleY + 1, BORDER_COLOR);

        buttons.render(graphics, font, mouseX, mouseY);

        // 视口底板与边框画在视口坐标下，之后再进内容坐标并裁剪。
        graphics.fill(scrollViewport.x() - 1, scrollViewport.y() - 1,
                scrollViewport.right() + 1, scrollViewport.bottom() + 1, BORDER_COLOR);
        graphics.fill(scrollViewport.x(), scrollViewport.y(),
                scrollViewport.right(), scrollViewport.bottom(), PANEL_COLOR);
        boolean insideScroll = scroll.contains(mouseX, mouseY);
        int contentX = insideScroll ? (int) scroll.toContentX(mouseX) : -1;
        int contentY = insideScroll ? (int) scroll.toContentY(mouseY) : -1;
        scroll.push(graphics);
        try {
            scrollRows.render(graphics, font, contentX, contentY);
        } finally {
            scroll.pop(graphics);
        }
        scroll.renderScrollbar(graphics, UiControlStyle.DARK);

        // 模态层自己负责 flush / z=400 / 独占输入；宿主只写可用区域。
        overlays.setBounds(width, height);
        overlays.render(graphics, font, mouseX, mouseY, partialTick);
        if (!overlays.isOpen()) renderTooltip(graphics, mouseX, mouseY);
    }

    // 同一位置只画一个 tooltip：按钮行优先，指针在视口内时才问滚动内容。
    private void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        UiTarget target = buttons.targetAt(mouseX, mouseY);
        if (target == null && scroll.contains(mouseX, mouseY)) {
            target = scrollRows.targetAt(scroll.toContentX(mouseX), scroll.toContentY(mouseY));
        }
        if (target != null && !target.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, target.tooltip(), mouseX, mouseY);
        }
    }

    // 读数直接取自对象（焦点管理器 / 滚动视口），不重新计算布局。
    private void refreshStatus() {
        status = text("status", activations, focus.focusedIndex(), focus.size(),
                scroll.offset(), scroll.maxOffset());
    }

    // ---------- 灯箱 ----------

    // 打开灯箱：内容视图只吃一张 LightboxImage，模态接入交给 LightboxOverlay。
    private void openLightbox() {
        if (overlays.isOpen()) return;
        LightboxImage image = LightboxImage.whole(SAMPLE_IMAGE_ID, SAMPLE_TEXTURE,
                SAMPLE_IMAGE_WIDTH, SAMPLE_IMAGE_HEIGHT, text("lightbox_title"), text("lightbox_desc"));
        UiLightbox lightbox = new UiLightbox(labels(), new UiImageView(image, missingText()),
                overlays::close);
        overlays.setBounds(width, height);
        // 只有按钮当前确实持有焦点（键盘到达）时，关闭灯箱才会把焦点还给它。
        overlays.open(new LightboxOverlay(overlays, lightbox), lightboxButton);
        refreshStatus();
    }

    // 缺图占位沿用通用灯箱键，示例不重复新增。
    private static Component missingText() {
        return Component.translatable("screen.unsuspiciousblock.lightbox.missing");
    }

    // 外壳文案复用通用灯箱键（kit 不持有文案 key）。
    private static UiLightbox.Labels labels() {
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

    // ---------- 输入 ----------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 模态优先：打开期间它吞掉全部鼠标输入，下层既点不到也不会弹提示。
        if (overlays.mouseClicked(mouseX, mouseY, button)) return true;
        // 模态没接管这次点击：收掉上一轮恢复的焦点轮廓，并清掉键盘焦点（鼠标点击不夺取焦点）。
        // 两个调用缺一不可：前者是 OverlayLayer 侧的「已还给入口的焦点」，后者是焦点管理器里的当前焦点。
        overlays.clearRestoredFocus();
        focus.clearFocus();
        if (button == 0) {
            if (buttons.mousePressed(mouseX, mouseY, button)) return true;
            if (scroll.contains(mouseX, mouseY)) {
                if (scroll.hitScrollbar(mouseX, mouseY)) return scroll.mousePressed(mouseX, mouseY, button);
                return scrollRows.mousePressed(scroll.toContentX(mouseX), scroll.toContentY(mouseY), button);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (overlays.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (button == 0 && scroll.mouseDragged(mouseY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (overlays.mouseReleased(mouseX, mouseY, button)) return true;
        if (button == 0) {
            scroll.mouseReleased();
            buttons.mouseReleased(button);
            scrollRows.mouseReleased(button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overlays.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (scroll.contains(mouseX, mouseY) && scroll.scrollBy(scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 灯箱打开时 ESC / 方向键 / Tab 都由它处理：ESC 关灯箱而不是关本页。
        if (overlays.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { onClose(); return true; }
        if (focus.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 模态期间的字符也不落到下层（灯箱本身不做文字编辑，因此这里只是吞掉）。
        return overlays.charTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    @Override
    public void tick() {
        // 读数按秒刷新即可；交互（激活）时会立刻刷新一次。
        if (++ticks % 20 == 0) refreshStatus();
    }

    @Override
    public void onClose() { Objects.requireNonNull(minecraft).setScreen(parent); }

    private static Component text(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
}
