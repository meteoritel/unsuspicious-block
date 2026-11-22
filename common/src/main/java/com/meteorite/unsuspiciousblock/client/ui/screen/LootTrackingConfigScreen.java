package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiModal;
import com.meteorite.meteosuikit.api.control.UiModalStack;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.control.UiWidget;
import com.meteorite.meteosuikit.api.control.UiWidgetRow;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiFocusTarget;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiLinearLayout;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextScroll;
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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/** Fabric、NeoForge 与考古笔记共用的服务端世界规则草稿。 */
public final class LootTrackingConfigScreen extends ConfigFormScreen {
    private static final String KEY = LootTrackingPreviewDialog.KEY;
    private static final int RULE_ACTION_HEIGHT = 18;
    private static final int RULE_ACTION_GAP = 6;
    private static final UiLinearLayout.Child BULK_ACTION_WIDTH = UiLinearLayout.Child.remain(0, 86);
    private final UiModalStack modals = new UiModalStack(theme());
    private final UiControl enableAll = createControl();
    private final UiControl disableAll = createControl();
    private final BuiltinRuleList builtinRules = new BuiltinRuleList();
    private final Set<String> disabledBuiltinRules = new TreeSet<>();
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
    private boolean followBuiltins;
    private boolean overrideComponents;
    private @Nullable Draft baseline;
    private @Nullable UpdateTrackedLootTablePayload.Replace pendingDraft;
    private long draftResources;
    private long draftRevision = -1;
    private int pendingRequest;
    private boolean requested;

    public LootTrackingConfigScreen(Screen parent) {
        super(parent, text("title"));
        reload = createControl();
        reload.configure(Minecraft.getInstance().font, text("load_current"), null, List.of(),
                this::reviewLatestVersion);
        reload.setVisible(false);
        addBuiltinRuleControls();
        additional = addRuleField("additional", "archaeology/\nminecraft:chests/buried_treasure");
        exclusions = addRuleField("excluded", "minecraft:chests/ancient_city");
        advanced = createControl();
        addField(Component.empty(), advanced, 20);
        componentsOverride = createControl();
        setComponentsOverride(false);
        addField(Component.empty(), componentsOverride, 20);
        components = addRuleField("components", "relics:data");
        logLimit = addTextField(text("log_limit", LootTrackingSettings.DEFAULT_LOG_ENTRIES), "");
        timeout = addTextField(text("timeout", LootTrackingSettings.DEFAULT_TIMEOUT_TICKS), "");
        timeoutDescription = new UiControl();
        timeoutDescription.setEnabled(false);
        addField(Component.empty(), timeoutDescription, 18);
        modals.setOnScopeChanged(this::refreshUiState);
    }

    private void addBuiltinRuleControls() {
        Font uiFont = Minecraft.getInstance().font;
        enableAll.configure(uiFont, text("enable_all"), null, List.of(), this::enableAllBuiltins);
        disableAll.configure(uiFont, text("disable_all"), null, List.of(), this::disableAllBuiltins);
        UiWidgetRow actions = new UiWidgetRow(RULE_ACTION_GAP, UiWidgetRow.Alignment.START);
        actions.add(enableAll, BULK_ACTION_WIDTH);
        actions.add(disableAll, BULK_ACTION_WIDTH);
        actions.add(reload, UiLinearLayout.Child.remain(0, 140));
        addRow(actions, RULE_ACTION_HEIGHT);
        addCompactSection(builtinRules, builtinRules.height());
        builtinRules.refreshLabels();
    }

    private void enableAllBuiltins() {
        followBuiltins = true;
        for (Builtin rule : LootTrackingRules.BUILTINS) disabledBuiltinRules.remove(rule.id());
        builtinRules.refreshLabels();
    }

    private void disableAllBuiltins() {
        followBuiltins = false;
        builtinRules.refreshLabels();
    }

    private boolean isBuiltinEnabled(Builtin rule) {
        return followBuiltins && !disabledBuiltinRules.contains(rule.id());
    }

    private void toggleBuiltin(Builtin rule) {
        if (isBuiltinEnabled(rule)) {
            disabledBuiltinRules.add(rule.id());
        } else {
            // 从全部禁用恢复单条时，其余内置规则继续关闭。
            if (!followBuiltins) {
                followBuiltins = true;
                for (Builtin builtin : LootTrackingRules.BUILTINS) disabledBuiltinRules.add(builtin.id());
            }
            disabledBuiltinRules.remove(rule.id());
        }
        builtinRules.refreshLabels();
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
        boolean editable = canEdit();
        components.setEnabled(editable && overrideComponents);
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
        enableAll.setEnabled(editable);
        disableAll.setEnabled(editable);
        for (UiControl toggle : builtinRules.inputChildren()) toggle.setEnabled(editable);
        reload.setVisible(snapshot != null && snapshot.settingsJson() != null
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
        disabledBuiltinRules.clear();
        enableAllBuiltins();
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
        return new Draft(followBuiltins, List.copyOf(disabledBuiltinRules), additional.value(), exclusions.value(),
                overrideComponents, components.value(), logLimit.value(), timeout.value());
    }

    private void writeDraft(Draft draft) {
        followBuiltins = draft.followBuiltins();
        disabledBuiltinRules.clear();
        disabledBuiltinRules.addAll(draft.disabled());
        builtinRules.refreshLabels();
        additional.setValue(draft.additional());
        exclusions.setValue(draft.excluded());
        setComponentsOverride(draft.overrideComponents());
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

    private void setComponentsOverride(boolean selected) {
        overrideComponents = selected;
        Component state = Component.translatable(selected ? "options.on" : "options.off");
        componentsOverride.configure(Minecraft.getInstance().font, text("toggle", text("override_components"), state),
                null, List.of(), () -> setComponentsOverride(!overrideComponents));
    }

    private UiControl createControl() {
        UiControl control = new UiControl();
        control.setTheme(theme(), UiControlRole.SECONDARY);
        control.setFocusable(true);
        return control;
    }

    private final class BuiltinRuleList implements UiWidget, UiInputContainer {
        private static final int ROW_HEIGHT = 16;
        private static final int ROW_GAP = 3;
        private static final int TOGGLE_WIDTH = 56;
        private static final int TOGGLE_GAP = 8;
        private final UiChildren<UiControl> toggles = new UiChildren<>();
        private final List<Component> labels = LootTrackingRules.BUILTINS.stream()
                .map(rule -> text("builtin." + rule.id())).toList();
        private UiRect bounds = new UiRect(0, 0, 0, 0);

        private BuiltinRuleList() {
            for (Builtin rule : LootTrackingRules.BUILTINS) {
                UiControl toggle = createControl();
                toggle.setLayoutKey("loot-tracking/builtin/" + rule.id());
                toggles.add(toggle);
            }
        }

        private int height() {
            return toggles.size() * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;
        }

        private void refreshLabels() {
            Font uiFont = Minecraft.getInstance().font;
            for (int index = 0; index < toggles.size(); index++) {
                Builtin rule = LootTrackingRules.BUILTINS.get(index);
                Component label = labels.get(index);
                Component state = text(isBuiltinEnabled(rule) ? "rule_enabled" : "rule_disabled");
                UiControl toggle = toggles.get(index);
                toggle.configure(uiFont, state, null, List.of(label, Component.literal(rule.selector())),
                        () -> toggleBuiltin(rule));
                toggle.setAccessibleName(text("toggle", label, state));
            }
        }

        @Override
        public UiRect bounds() {
            return bounds;
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            bounds = new UiRect(x, y, width, height);
            int toggleWidth = Math.min(width, TOGGLE_WIDTH);
            for (int index = 0; index < toggles.size(); index++) {
                toggles.get(index).setBounds(bounds.right() - toggleWidth, y + index * (ROW_HEIGHT + ROW_GAP),
                        toggleWidth, ROW_HEIGHT);
            }
        }

        @Override
        public UiChildren<UiControl> inputChildren() {
            return toggles;
        }

        @Override
        public @Nullable UiPointerHit hitTest(double x, double y) {
            return UiInputContainer.super.hitTest(x, y);
        }

        @Override
        public long focusRevision() {
            return UiInputContainer.super.focusRevision();
        }

        @Override
        public void addFocusTargets(Consumer<UiFocusTarget> out) {
            UiInputContainer.super.addFocusTargets(out);
        }

        @Override
        public void render(GuiGraphics graphics, Font font, UiPointer pointer) {
            UiPointer resolved = pointer.resolve(this);
            for (int index = 0; index < toggles.size(); index++) {
                UiControl toggle = toggles.get(index);
                UiRect button = toggle.bounds();
                int labelWidth = Math.max(0, button.x() - bounds.x() - TOGGLE_GAP);
                int labelY = button.y() + (button.height() - font.lineHeight) / 2;
                String label = TextScroll.trimToWidth(font, labels.get(index).getString(), labelWidth);
                graphics.drawString(font, label, bounds.x(), labelY, UiTextScheme.DARK.body(), false);
                toggle.render(graphics, font, resolved);
            }
        }
    }

    private static boolean isConnected() {
        return Minecraft.getInstance().level != null;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }
}
