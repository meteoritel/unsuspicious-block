package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiListView;
import com.meteorite.meteosuikit.api.control.UiModal;
import com.meteorite.meteosuikit.api.control.UiModalStack;
import com.meteorite.meteosuikit.api.style.UiStyleSource;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Preview;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 规则页与单表操作共用的服务端收录预览。 */
final class LootTrackingPreviewDialog {
    static final String KEY = "unsuspiciousblock.config.loot_tracking.";

    private LootTrackingPreviewDialog() {}

    static void open(UiModalStack modals, Supplier<? extends UiStyleSource> theme, Font font,
                     int screenHeight, Preview preview, Runnable apply) {
        List<Component> rows = new ArrayList<>();
        append(rows, "preview.added", preview.added());
        append(rows, "preview.removed", preview.removed());
        append(rows, "preview.positions", preview.changedPositions());
        append(rows, "preview.parents", preview.parents());
        append(rows, "preview.unmatched", preview.unmatchedSelectors());
        append(rows, "preview.merged", preview.mergedVariants());
        if (rows.isEmpty()) rows.add(Component.translatable(KEY + "preview.no_change"));
        UiListView<Component> changes = new UiListView<>(theme, (graphics, rowFont, row, index, bounds, selected, hovered, focused) ->
                graphics.drawString(rowFont, TextScroll.trimToWidth(rowFont, row.getString(), bounds.width()),
                        bounds.x(), bounds.y() + 4, theme.get().textColor(true), false));
        changes.setItems(rows);
        changes.setRowHeight(16);
        modals.push(UiModal.create(theme, font)
                .title(Component.translatable(KEY + "preview.title"))
                .preferredWidth(420)
                .contentWidget(changes, Math.min(rows.size() * 16 + 8, Math.max(26, screenHeight - 92)))
                .confirm(Component.translatable("unsuspiciousblock.config.save"), apply::run)
                .cancel(Component.translatable("gui.cancel")));
    }

    private static void append(List<Component> rows, String key, List<?> values) {
        values.forEach(value -> rows.add(Component.translatable(KEY + key, value.toString())));
    }
}
