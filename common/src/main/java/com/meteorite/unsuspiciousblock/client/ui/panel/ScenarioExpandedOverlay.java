package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiDocumentLightboxContent;
import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * 场景灯箱的业务文案与内容装配，阅读交互由 kit 持有。
 */
final class ScenarioExpandedOverlay {
    /**
     * 灯箱通用文案：外壳的按钮与 tooltip 全部经由它取本地化文本；实例无状态，可复用。
     */
    static final UiLightbox.Labels LABELS = new UiLightbox.Labels() {
        @Override
        public Component close() {
            return text("close");
        }

        @Override
        public Component zoomIn() {
            return text("zoom_in");
        }

        @Override
        public Component zoomOut() {
            return text("zoom_out");
        }

        @Override
        public Component fit() {
            return ScenarioSession.text("frame.reset");
        }

        @Override
        public Component panLock() {
            return ScenarioSession.text("frame.pan_lock");
        }

        @Override
        public Component panUnlock() {
            return ScenarioSession.text("frame.pan_unlock");
        }

        @Override
        public Component previous() {
            return text("previous");
        }

        @Override
        public Component next() {
            return text("next");
        }

        @Override
        public Component position(int index, int total) {
            return text("position", index, total);
        }
    };

    private ScenarioExpandedOverlay() {
    }

    static UiDocumentLightboxContent create(ScenarioDetailPanel owner, Font font) {
        var content = new UiDocumentLightboxContent(TextMeasurer.of(font), () -> UnsuspiciousUiTheme.PARCHMENT,
                ScenarioSession.text("conditions.heading"));
        content.setContentSource(() -> ScenarioPageBuilder.copyForLightbox(owner.tree()));
        content.setBackground(ScenarioDetailPanel::renderConditionBackground);
        return content;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.lightbox." + key, args);
    }
}
