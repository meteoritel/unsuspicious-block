package com.meteorite.unsuspiciousblock.client.modmenu;

import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.unsuspiciousblock.client.ui.screen.ConfigFormScreen;
import com.meteorite.unsuspiciousblock.platform.FabricLootTableConfig;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Arrays;
import java.util.List;

/** 当前世界的 Fabric 配置；可写性随集成服务器生命周期重新读取。 */
public final class ServerLootConfigScreen extends ConfigFormScreen {
    private final FabricLootTableConfig config = (FabricLootTableConfig) Services.LOOT_TABLE_CONFIG;
    private final UiTextArea prefixes;
    private final UiTextInput maxEntries;
    private final UiTextInput timeout;
    private boolean resetExclusionsPending;

    public ServerLootConfigScreen(Screen parent) {
        super(parent, Component.translatable("unsuspiciousblock.config.server.title"));
        prefixes = addMultilineField(Component.translatable("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes"),
                String.join("\n", config.getArchaeologyPathPrefixes()));
        maxEntries = addTextField(Component.translatable("unsuspiciousblock.configgui.journal.max_log_entries_per_table"),
                Integer.toString(config.getMaxLogEntriesPerTable())).setMaxLength(5);
        timeout = addTextField(Component.translatable("unsuspiciousblock.configgui.journal.tracking_timeout_ticks"),
                Long.toString(config.getTrackingTimeoutTicks())).setMaxLength(6);
        maxEntries.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
        timeout.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
    }

    @Override protected boolean canEdit() { return config.hasActiveServerConfig(); }

    @Override protected void resetDraft() {
        prefixes.setValue(String.join("\n", ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES));
        maxEntries.setValue(Integer.toString(ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE));
        timeout.setValue(Long.toString(ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS));
        resetExclusionsPending = true;
    }

    @Override protected boolean saveDraft() {
        int entries;
        long ticks;
        try {
            entries = maxEntries.value().isEmpty() ? ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE
                    : Integer.parseInt(maxEntries.value());
            ticks = timeout.value().isEmpty() ? ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS
                    : Long.parseLong(timeout.value());
        } catch (NumberFormatException exception) {
            showValidation(Component.translatable("unsuspiciousblock.config.invalid_number"));
            return false;
        }
        if (entries < ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE || entries > ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE) {
            showValidation(Component.translatable("unsuspiciousblock.config.invalid_range",
                    ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE, ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE));
            return false;
        }
        if (ticks < ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS || ticks > ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS) {
            showValidation(Component.translatable("unsuspiciousblock.config.invalid_range",
                    ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS, ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS));
            return false;
        }
        List<String> rules = Arrays.stream(prefixes.value().split("\n", -1)).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return config.save(rules, resetExclusionsPending ? List.of() : config.getExcludedLootTables(), entries, ticks);
    }

    @Override protected Component notice() {
        return Component.translatable(!canEdit() ? "unsuspiciousblock.config.server_managed"
                : resetExclusionsPending ? "unsuspiciousblock.config.defaults_pending" : "unsuspiciousblock.config.server.integrated");
    }
}
