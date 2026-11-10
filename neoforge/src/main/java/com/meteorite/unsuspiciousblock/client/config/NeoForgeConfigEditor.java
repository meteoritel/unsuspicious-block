package com.meteorite.unsuspiciousblock.client.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.unsuspiciousblock.client.ui.screen.ConfigFormScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** 从本模组 spec 派生完整配置草稿，全部验证成功后统一写入原平台配置。 */
public final class NeoForgeConfigEditor extends ConfigFormScreen {
    private record Binding(ModConfigSpec.ConfigValue<?> value, Supplier<Object> read, Runnable reset) { }
    private final ModConfig config;
    private final ModConfigSpec spec;
    private final List<Binding> bindings = new ArrayList<>();

    public NeoForgeConfigEditor(Screen parent, ModConfig config) {
        super(parent, Component.translatable("unsuspiciousblock.config.file_title", config.getFileName()));
        this.config = config;
        spec = (ModConfigSpec) config.getSpec();
        addValues(spec.getValues());
    }

    private void addValues(UnmodifiableConfig values) {
        for (UnmodifiableConfig.Entry entry : values.entrySet()) {
            Object value = entry.getRawValue();
            if (value instanceof UnmodifiableConfig section) addValues(section);
            else addValue((ModConfigSpec.ConfigValue<?>) value);
        }
    }

    private void addValue(ModConfigSpec.ConfigValue<?> value) {
        String path = String.join(".", value.getPath());
        String key = value.getSpec().getTranslationKey();
        Component label = Component.translatable(key == null ? "unsuspiciousblock.configuration." + path : key);
        Object initial = value.getRaw();
        Object defaultValue = value.getDefault();
        Supplier<Object> read;
        Consumer<Object> write;
        if (defaultValue instanceof Boolean) {
            UiControl toggle = new UiControl();
            toggle.setFocusable(true);
            toggle.setTheme(theme(), UiControlRole.SECONDARY);
            Consumer<Object> update = next -> {
                toggle.setSelected((Boolean) next);
                refreshToggle(toggle);
            };
            update.accept(initial);
            addField(label, toggle, 20);
            read = toggle::isSelected;
            write = update;
        } else if (defaultValue instanceof List<?>) {
            UiTextArea editor = addMultilineField(label, formatList((List<?>) initial));
            read = () -> editor.value().isEmpty() ? List.of() : Arrays.asList(editor.value().split("\n", -1));
            write = next -> editor.setValue(formatList((List<?>) next));
        } else {
            UiTextInput editor = addTextField(label, initial.toString()).setMaxLength(Integer.MAX_VALUE);
            Function<String, Object> parse = switch (defaultValue) {
                case Integer ignored -> text -> Integer.decode(text.trim());
                case Long ignored -> text -> Long.decode(text.trim());
                case Double ignored -> text -> Double.parseDouble(text.trim());
                default -> text -> text;
            };
            read = () -> parse.apply(editor.value());
            write = next -> editor.setValue(next.toString());
        }
        bindings.add(new Binding(value, read, () -> write.accept(defaultValue)));
    }

    private static void refreshToggle(UiControl toggle) {
        toggle.configure(Minecraft.getInstance().font, Component.translatable(toggle.isSelected() ? "options.on" : "options.off"),
                null, List.of(), () -> { toggle.setSelected(!toggle.isSelected()); refreshToggle(toggle); });
    }

    private static String formatList(List<?> values) {
        return String.join("\n", values.stream().map(Object::toString).toList());
    }

    @Override protected boolean canEdit() { return NeoForgeConfigScreen.canEdit(config); }
    @Override protected Component notice() { return NeoForgeConfigScreen.status(config); }
    @Override protected void resetDraft() { bindings.forEach(binding -> binding.reset().run()); }

    @Override protected boolean saveDraft() {
        List<Object> drafts = new ArrayList<>();
        for (Binding binding : bindings) {
            Object draft;
            try {
                draft = binding.read().get();
            } catch (NumberFormatException exception) {
                showInvalid(binding);
                return false;
            }
            if (!binding.value().getSpec().test(draft)) {
                showInvalid(binding);
                return false;
            }
            drafts.add(draft);
        }
        for (int i = 0; i < bindings.size(); i++) setValidated(bindings.get(i).value(), drafts.get(i));
        spec.save();
        return true;
    }

    private void showInvalid(Binding binding) {
        showValidation(Component.translatable("unsuspiciousblock.config.invalid_field", String.join(".", binding.value().getPath())));
    }

    // 草稿按当前字段类型解析，并已通过同一 ConfigValue 的平台校验器。
    @SuppressWarnings("unchecked")
    private static <T> void setValidated(ModConfigSpec.ConfigValue<T> value, Object draft) { value.set((T) draft); }
}
