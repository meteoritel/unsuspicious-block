package com.meteorite.unsuspiciousblock.client.ui.kit.debug;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.kit.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiMetrics;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTransform;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** S1 临时人工验证页；仅开发环境显式启用，S1-t 双平台验收完成后移除。 */
public final class UiKitDebugScreen extends Screen {
    private static final String PREFIX = "screen.unsuspiciousblock.ui_kit_debug.";
    private static final int BRANCH_COLOR = 0xFF896C48;
    private final Screen parent;
    private final UiTransform frameTransform = new UiTransform();
    private UiDocument document;
    private UiRect frameBounds;
    private boolean dragging;
    private int outerScale = 1;
    private int ticks;
    private Component instructions;
    private Component diagnostics = Component.empty();

    public UiKitDebugScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
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
        int frameWidth = Math.clamp(width / outerScale - 16, 1, 152);
        int frameHeight = Math.clamp((height - 64) / outerScale - font.lineHeight - 7, 1, 166);
        int docHeight = frameHeight + font.lineHeight + 7;
        int x = (width / outerScale - frameWidth) / 2;
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xEE181818);
        graphics.drawString(font, title, 8, 6, UiTextPalette.Dark.TITLE, false);
        graphics.drawString(font, instructions, 8, 18, UiTextPalette.Dark.BODY, false);
        graphics.drawString(font, diagnostics, 8, height - 12, UiTextPalette.Dark.BODY, false);
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
        } finally {
            graphics.pose().popPose();
        }
        UiTarget target = document.hit(mouseX / (double) outerScale, mouseY / (double) outerScale);
        if (target != null && !target.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, target.tooltip(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX / outerScale;
        double y = mouseY / outerScale;
        if (button == 0) {
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
        if (button == 0 && dragging) {
            frameTransform.panBy(dragX / outerScale, dragY / outerScale);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX / outerScale;
        double y = mouseY / outerScale;
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
        if (keyCode == GLFW.GLFW_KEY_P) {
            outerScale = outerScale == 1 ? 2 : 1;
            init();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        diagnostics = text("metrics", metrics.builds(), metrics.layouts(), metrics.buildNanos() / 1000,
                metrics.layoutNanos() / 1000, metrics.renderNanos() / 1000, metrics.hitNanos() / 1000,
                (int) (frameTransform.scale() * 100), outerScale);
    }

    @Override
    public void onClose() { Objects.requireNonNull(minecraft).setScreen(parent); }

    private static Component text(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
}
