package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiModal;
import com.meteorite.meteosuikit.api.control.UiModalStack;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.Builtin;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingSettings;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTablePattern;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.ConfigView;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload.Receipt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fabric、NeoForge 与考古笔记共用的服务端世界规则草稿。 */
public final class LootTrackingConfigScreen extends ConfigFormScreen {
    private static final String KEY = LootTrackingPreviewDialog.KEY;
    private final UiModalStack modals = new UiModalStack(theme());
    private final UiControl followBuiltins;
    private final Map<Builtin, UiControl> builtinRules = new LinkedHashMap<>();
    private final UiTextArea additional;
    private final UiTextArea exclusions;
    private final UiControl componentsOverride;
    private final UiTextArea components;
    private final UiTextInput logLimit;
    private final UiTextInput timeout;
    private final UiControl reload;
    private final UiControl advanced;
    private final UiControl timeoutDescription;
    private boolean advancedOpen;
    private @Nullable Draft baseline;
    private @Nullable UpdateTrackedLootTablePayload.Replace pendingDraft;
    private long draftResources;
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
                this::reviewLatestVersion);
        addField(Component.empty(), reload, 20);
        followBuiltins = addToggle(text("follow"));
        for (Builtin rule : LootTrackingRules.BUILTINS) {
            builtinRules.put(rule, addToggle(Component.literal(rule.selector())));
        }
        additional = addRuleField("additional", "archaeology/\nminecraft:chests/buried_treasure");
        exclusions = addRuleField("excluded", "minecraft:chests/ancient_city");
        advanced = addToggle(text("advanced"));
        advanced.configure(Minecraft.getInstance().font, text("show_advanced"), null, List.of(),
                () -> advancedOpen = !advancedOpen);
        componentsOverride = addToggle(text("override_components"));
        components = addRuleField("components", "relics:data");
        logLimit = addTextField(text("log_limit", LootTrackingSettings.DEFAULT_LOG_ENTRIES), "");
        timeout = addTextField(text("timeout", LootTrackingSettings.DEFAULT_TIMEOUT_TICKS), "");
        timeoutDescription = new UiControl();
        timeoutDescription.setEnabled(false);
        addField(Component.empty(), timeoutDescription, 18);
        modals.setOnScopeChanged(this::refreshUiState);
    }

    @Override
    protected void init() {
        super.init();
        if (!requested && isConnected()) {
            requested = true;
            LootTableManagementClientState.requestConfiguration();
        }
    }

    @Override
    protected void prepareUi() {
        ConfigView snapshot = LootTableManagementClientState.configuration();
        if (draftRevision < 0) loadSnapshot(snapshot);
        Receipt receipt = LootTableManagementClientState.receipt(pendingRequest);
        if (receipt != null && receipt.result() != EditResult.SAVED) {
            int request = pendingRequest;
            pendingRequest = 0;
            if (receipt.result() == EditResult.PREVIEWED && pendingDraft != null) {
                var preview = LootTableManagementClientState.preview(request);
                if (preview != null) {
                    var edit = pendingDraft;
                    long revision = draftRevision;
                    long resources = draftResources;
                    LootTrackingPreviewDialog.open(modals, theme(), font, height, preview, () ->
                            pendingRequest = LootTableManagementClientState.submit(revision, resources, edit));
                }
            } else {
                if (receipt.result() == EditResult.APPLIED || receipt.result() == EditResult.UNCHANGED
                        || receipt.result() == EditResult.APPLY_FAILED) loadSnapshot(snapshot);
                showValidation(resultMessage(receipt.result()));
            }
            pendingDraft = null;
        }
        super.prepareUi();
        components.setEnabled(canEdit() && componentsOverride.isSelected());
        componentsOverride.setVisible(advancedOpen);
        components.setVisible(advancedOpen);
        logLimit.setVisible(advancedOpen);
        timeout.setVisible(advancedOpen);
        timeoutDescription.setVisible(advancedOpen);
        if (advancedOpen) {
            try {
                String value = timeout.value().trim();
                long ticks = value.isEmpty() ? LootTrackingSettings.DEFAULT_TIMEOUT_TICKS : Long.parseLong(value);
                timeoutDescription.configure(font, text("timeout_time", ticks, ticks / 20.0), null, List.of(), null);
            } catch (NumberFormatException exception) {
                timeoutDescription.configure(font, text("timeout_range", LootTrackingSettings.MIN_TIMEOUT_TICKS,
                        LootTrackingSettings.MAX_TIMEOUT_TICKS), null, List.of(), null);
            }
        }
        advanced.configure(font, text(advancedOpen ? "hide_advanced" : "show_advanced"),
                null, List.of(), () -> advancedOpen = !advancedOpen);
        for (UiControl toggle : builtinRules.values()) toggle.setEnabled(canEdit() && followBuiltins.isSelected());
        reload.setEnabled(snapshot != null && snapshot.settingsJson() != null
                && isStale(snapshot) && pendingRequest == 0 && isConnected());
        modals.prepareUi();
    }

    private boolean isStale(ConfigView snapshot) {
        return draftRevision != snapshot.revision() || draftResources != snapshot.resourceRevision();
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
        if (isStale(snapshot) && pendingRequest == 0) return text("conflict");
        if (!snapshot.canEdit()) return text("read_only");
        if (pendingRequest != 0 && pendingDraft != null && snapshot.revision() == draftRevision) {
            return text("previewing");
        }
        return applicationMessage(snapshot);
    }

    static Component applicationMessage(ConfigView snapshot) {
        return text("application." + snapshot.application().name().toLowerCase(Locale.ROOT),
                snapshot.revision(), snapshot.appliedRevision());
    }

    @Override
    protected UiInputScope inputScope() {
        return modals.isEmpty() ? super.inputScope() : modals.top().inputScope();
    }

    @Override
    protected void layoutUi() {
        super.layoutUi();
        modals.setBounds(0, 0, width, height);
    }

    @Override
    protected void advanceUiFrame() {
        modals.tick();
    }

    @Override
    protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        super.renderThemedUi(graphics, x, y, delta);
        modals.render(graphics, font, UiPointer.at(x, y));
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
        if (snapshot == null || isStale(snapshot)) {
            showValidation(text("conflict"));
            return false;
        }
        Draft draft = readDraft();
        try {
            List<String> additionalRules = validateLines(draft.additional(), "additional", true);
            List<String> excludedTables = validateLines(draft.excluded(), "excluded", false);
            List<String> signatureExclusions = draft.overrideComponents()
                    ? validateLines(draft.components(), "components", false) : null;
            LootTrackingSettings settings = new LootTrackingSettings(draft.followBuiltins(), draft.disabled(),
                    additionalRules, excludedTables, signatureExclusions,
                    draft.logLimit().isBlank() ? null : Integer.valueOf(draft.logLimit().trim()),
                    draft.timeout().isBlank() ? null : Long.valueOf(draft.timeout().trim()));
            String encoded = LootTrackingConfigFile.encodeSettings(settings);
            if (encoded.length() > UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS) {
                showValidation(text("too_large"));
                return false;
            }
            showValidation(Component.empty());
            pendingDraft = new UpdateTrackedLootTablePayload.Replace(encoded);
            pendingRequest = LootTableManagementClientState.requestPreview(draftRevision, draftResources, pendingDraft);
        } catch (InvalidLine exception) {
            showValidation(exception.message);
        } catch (NumberFormatException exception) {
            showValidation(Component.translatable("unsuspiciousblock.config.invalid_number"));
        } catch (IllegalArgumentException exception) {
            showValidation(text("invalid_value", exception.getMessage()));
        }
        return false;
    }

    private static List<String> validateLines(String value, String field, boolean selector) {
        List<String> result = new ArrayList<>();
        List<String> lines = value.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty()) continue;
            var pattern = selector ? LootTablePattern.parse(line) : null;
            var exact = selector ? null : ResourceLocation.tryParse(line);
            boolean valid = selector ? pattern != null && pattern.isValidSelector()
                    : exact != null && !exact.getPath().isEmpty() && line.equals(exact.toString());
            if (!valid) throw new InvalidLine(text("invalid_line", text(field), index + 1, line));
            result.add(line);
        }
        return List.copyOf(result);
    }

    private static final class InvalidLine extends RuntimeException {
        private final Component message;

        private InvalidLine(Component message) { this.message = message; }
    }

    @Override
    public void removed() {
        if (requested) {
            LootTableManagementClientState.closeSnapshot();
            requested = false;
        }
        super.removed();
    }

    @Override
    public void onClose() {
        if (pendingRequest != 0 && isConnected()) return;
        if (!isConnected() || baseline == null || readDraft().equals(baseline)) {
            super.onClose();
            return;
        }
        modals.push(UiModal.create(theme(), font).title(text("discard_title"))
                .message(text("discard_message"))
                .confirm(text("discard"), super::onClose)
                .cancel(text("continue_editing")));
    }

    static Component resultMessage(EditResult result) {
        return text(result.name().toLowerCase(Locale.ROOT));
    }

    private void reviewLatestVersion() {
        ConfigView snapshot = LootTableManagementClientState.configuration();
        if (snapshot == null || snapshot.settingsJson() == null) return;
        Draft recent = Draft.from(LootTrackingConfigFile.decodeSettings(snapshot.settingsJson()));
        Draft draft = readDraft();
        if (baseline == null || draft.equals(baseline)) {
            loadSnapshot(snapshot);
            return;
        }
        modals.push(UiModal.create(theme(), font).title(text("conflict_title"))
                .message(text("rebase_message"))
                .confirm(text("keep_changes"), () -> {
                    Draft merged = draft.merge(baseline, recent);
                    loadSnapshot(snapshot);
                    writeDraft(merged);
                })
                .confirm(text("load_without_draft"), () -> loadSnapshot(snapshot))
                .cancel(text("continue_editing")));
    }

    private void loadSnapshot(@Nullable ConfigView snapshot) {
        if (snapshot == null || snapshot.settingsJson() == null) return;
        draftRevision = snapshot.revision();
        draftResources = snapshot.resourceRevision();
        baseline = Draft.from(LootTrackingConfigFile.decodeSettings(snapshot.settingsJson()));
        writeDraft(baseline);
        showValidation(Component.empty());
    }

    private Draft readDraft() {
        List<String> disabled = new ArrayList<>(unknownDisabledRules);
        builtinRules.forEach((rule, toggle) -> {
            if (!toggle.isSelected()) disabled.add(rule.id());
        });
        disabled.sort(String::compareTo);
        return new Draft(followBuiltins.isSelected(), List.copyOf(disabled), additional.value(), exclusions.value(),
                componentsOverride.isSelected(), components.value(), logLimit.value(), timeout.value());
    }

    private void writeDraft(Draft draft) {
        setToggle(followBuiltins, draft.followBuiltins());
        List<String> unknown = new ArrayList<>(draft.disabled());
        builtinRules.forEach((rule, toggle) -> setToggle(toggle, !unknown.remove(rule.id())));
        unknownDisabledRules = List.copyOf(unknown);
        additional.setValue(draft.additional());
        exclusions.setValue(draft.excluded());
        setToggle(componentsOverride, draft.overrideComponents());
        components.setValue(draft.components());
        logLimit.setValue(draft.logLimit());
        timeout.setValue(draft.timeout());
    }

    private UiTextArea addRuleField(String key, String example) {
        UiTextArea area = addMultilineField(text(key), "");
        area.setMaxLength(UpdateTrackedLootTablePayload.MAX_CONFIG_CHARS);
        area.setHint(Component.literal(example));
        area.setOnOverflow(() -> showValidation(text("too_large")));
        return area;
    }

    private record Draft(boolean followBuiltins, List<String> disabled, String additional, String excluded,
                         boolean overrideComponents, String components, String logLimit, String timeout) {
        private static Draft from(LootTrackingSettings settings) {
            return new Draft(settings.followBuiltinRules(), settings.disabledBuiltinRules(),
                    String.join("\n", settings.additionalTrackingRules()), String.join("\n", settings.excludedLootTables()),
                    settings.signatureExcludedComponents() != null, String.join("\n", settings.signatureExclusions()),
                    Objects.toString(settings.maxLogEntriesPerTable(), ""),
                    Objects.toString(settings.trackingTimeoutTicks(), ""));
        }

        private Draft merge(Draft initial, Draft recent) {
            return new Draft(keep(followBuiltins, initial.followBuiltins, recent.followBuiltins),
                    keep(disabled, initial.disabled, recent.disabled),
                    keep(additional, initial.additional, recent.additional), keep(excluded, initial.excluded, recent.excluded),
                    keep(overrideComponents, initial.overrideComponents, recent.overrideComponents),
                    keep(components, initial.components, recent.components), keep(logLimit, initial.logLimit, recent.logLimit),
                    keep(timeout, initial.timeout, recent.timeout));
        }

        private static <T> T keep(T draft, T initial, T recent) {
            return draft.equals(initial) ? recent : draft;
        }
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

    private static boolean isConnected() {
        return Minecraft.getInstance().level != null;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }
}
