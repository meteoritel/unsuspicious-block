package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.Builtin;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingSettings;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.ConfigView;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Receipt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fabric、NeoForge 与考古笔记共用的服务端世界规则草稿。 */
public final class LootTrackingConfigScreen extends ConfigFormScreen {
    private static final String KEY = "unsuspiciousblock.config.loot_tracking.";
    private final UiControl followBuiltins;
    private final Map<Builtin, UiControl> builtinRules = new LinkedHashMap<>();
    private final UiTextArea additional;
    private final UiTextArea exclusions;
    private final UiControl componentsOverride;
    private final UiTextArea components;
    private final UiTextInput logLimit;
    private final UiTextInput timeout;
    private final UiControl reload;
    private List<String> unknownDisabledRules = List.of();
    private long draftRevision = -1;
    private int pendingRequest;
    private boolean requested;

    public LootTrackingConfigScreen(Screen parent) {
        super(parent, text("title"));
        reload = new UiControl();
        reload.setTheme(theme(), UiControlRole.SECONDARY);
        reload.setFocusable(true);
        reload.configure(Minecraft.getInstance().font, text("load_current"), null, List.of(),
                () -> loadSnapshot(LootTableManagementClientState.configuration()));
        addField(Component.empty(), reload, 20);
        followBuiltins = addToggle(text("follow"));
        for (Builtin rule : LootTrackingRules.BUILTINS) {
            builtinRules.put(rule, addToggle(Component.literal(rule.selector())));
        }
        additional = addMultilineField(text("additional"), "");
        exclusions = addMultilineField(text("excluded"), "");
        componentsOverride = addToggle(text("override_components"));
        components = addMultilineField(text("components"), "");
        logLimit = addTextField(text("log_limit", LootTrackingSettings.DEFAULT_LOG_ENTRIES), "");
        timeout = addTextField(text("timeout", LootTrackingSettings.DEFAULT_TIMEOUT_TICKS), "");
    }

    @Override
    protected void init() {
        super.init();
        if (!requested && isConnected()) {
            requested = true;
            LootTableManagementClientState.requestSnapshot();
        }
    }

    @Override
    protected void prepareUi() {
        ConfigView snapshot = LootTableManagementClientState.configuration();
        if (draftRevision < 0) loadSnapshot(snapshot);
        Receipt receipt = LootTableManagementClientState.receipt(pendingRequest);
        if (receipt != null) {
            pendingRequest = 0;
            if (receipt.result() == EditResult.SAVED || receipt.result() == EditResult.UNCHANGED) {
                onClose();
                return;
            }
            showValidation(resultMessage(receipt.result()));
        }
        super.prepareUi();
        components.setEnabled(canEdit() && componentsOverride.isSelected());
        for (UiControl toggle : builtinRules.values()) {
            toggle.setEnabled(canEdit() && followBuiltins.isSelected());
        }
        reload.setEnabled(snapshot != null && snapshot.settingsJson() != null
                && draftRevision != snapshot.revision() && pendingRequest == 0 && isConnected());
    }

    @Override
    protected boolean canEdit() {
        ConfigView snapshot = LootTableManagementClientState.configuration();
        return isConnected() && draftRevision >= 0 && snapshot != null && snapshot.canEdit()
                && snapshot.isWritable() && snapshot.settingsJson() != null && pendingRequest == 0;
    }

    @Override
    protected Component notice() {
        if (!isConnected()) return text("enter_world");
        ConfigView snapshot = LootTableManagementClientState.configuration();
        if (snapshot == null) return text("loading");
        if (!snapshot.isWritable()) return text("read_error");
        if (snapshot.settingsJson() == null) return text("too_large");
        if (pendingRequest != 0) return text("saving");
        if (draftRevision != snapshot.revision()) return text("conflict");
        if (!snapshot.canEdit()) return text("read_only");
        return text("server_authority");
    }

    @Override
    protected Component resetLabel() {
        return text("reset_rules");
    }

    @Override
    protected void resetDraft() {
        setToggle(followBuiltins, true);
        builtinRules.values().forEach(toggle -> setToggle(toggle, true));
        unknownDisabledRules = List.of();
        additional.setValue("");
        exclusions.setValue("");
    }

    @Override
    protected boolean saveDraft() {
        ConfigView snapshot = LootTableManagementClientState.configuration();
        if (snapshot == null || snapshot.revision() != draftRevision) {
            showValidation(text("conflict"));
            return false;
        }
        List<String> disabled = new ArrayList<>(unknownDisabledRules);
        builtinRules.forEach((rule, toggle) -> {
            if (!toggle.isSelected()) disabled.add(rule.id());
        });
        try {
            String logText = logLimit.value().trim();
            String timeoutText = timeout.value().trim();
            LootTrackingSettings draft = new LootTrackingSettings(followBuiltins.isSelected(), disabled,
                    lines(additional), lines(exclusions), componentsOverride.isSelected() ? lines(components) : null,
                    logText.isEmpty() ? null : Integer.valueOf(logText),
                    timeoutText.isEmpty() ? null : Long.valueOf(timeoutText));
            String encoded = LootTrackingConfigFile.encodeSettings(draft);
            if (encoded.length() > UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS) {
                showValidation(text("too_large"));
                return false;
            }
            showValidation(Component.empty());
            pendingRequest = LootTableManagementClientState.submit(draftRevision, new UpdateTrackedLootTablePayload.Replace(encoded));
        } catch (NumberFormatException exception) {
            showValidation(Component.translatable("unsuspiciousblock.config.invalid_number"));
        } catch (IllegalArgumentException exception) {
            showValidation(text("invalid_value", exception.getMessage()));
        }
        return false;
    }

    @Override
    public void onClose() {
        if (pendingRequest == 0) super.onClose();
    }

    static Component resultMessage(EditResult result) {
        return text(result.name().toLowerCase(Locale.ROOT));
    }

    private void loadSnapshot(@Nullable ConfigView snapshot) {
        if (snapshot == null || snapshot.settingsJson() == null) return;
        LootTrackingSettings settings = LootTrackingConfigFile.decodeSettings(snapshot.settingsJson());
        draftRevision = snapshot.revision();
        setToggle(followBuiltins, settings.followBuiltinRules());
        List<String> unknown = new ArrayList<>(settings.disabledBuiltinRules());
        builtinRules.forEach((rule, toggle) -> setToggle(toggle, !unknown.remove(rule.id())));
        unknownDisabledRules = List.copyOf(unknown);
        additional.setValue(String.join("\n", settings.additionalTrackingRules()));
        exclusions.setValue(String.join("\n", settings.excludedLootTables()));
        setToggle(componentsOverride, settings.signatureExcludedComponents() != null);
        components.setValue(String.join("\n", settings.signatureExclusions()));
        logLimit.setValue(settings.maxLogEntriesPerTable() == null ? "" : settings.maxLogEntriesPerTable().toString());
        timeout.setValue(settings.trackingTimeoutTicks() == null ? "" : settings.trackingTimeoutTicks().toString());
        showValidation(Component.empty());
    }

    private UiControl addToggle(Component label) {
        UiControl toggle = new UiControl();
        toggle.setTheme(theme(), UiControlRole.SECONDARY);
        toggle.setFocusable(true);
        setToggle(toggle, false);
        addField(label, toggle, 20);
        return toggle;
    }

    private static void setToggle(UiControl toggle, boolean selected) {
        toggle.setSelected(selected);
        toggle.configure(Minecraft.getInstance().font, Component.translatable(selected ? "options.on" : "options.off"),
                null, List.of(), () -> setToggle(toggle, !toggle.isSelected()));
    }

    private static List<String> lines(UiTextArea area) {
        return area.value().lines().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static boolean isConnected() {
        return Minecraft.getInstance().level != null;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }
}
