package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.support.*;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** 场景读模型：假设、完整获取分支及独立联动说明；不在客户端推断路径可达性。 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 10;
    private static final int ITEM_INDENT = 14;
    private static final UiIcon UNKNOWN = new UiIcon.Sprite(ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/unknown_item.png"), 0, 0, 16, 16, 16, 16);
    enum Heading { SECTION, ENTRY }
    /** 相同完整路径语义共享一组候选，替代获取路径保持独立。 */
    private record Group(List<LootConditionInfo> conditions, List<LootConditionInfo> extras, LuckGate luck,
                         List<String> scenes, boolean uncertain, String source, String mode) {}
    private ScenarioPageBuilder() {}

    static List<UiNode> buildConditions(CatalogTableDto structure, String sceneKey, boolean showOther,
                                       UiAction toggleOther, Consumer<ResourceLocation> recommend,
                                       BiPredicate<String, String> visible, BiConsumer<String, String> locate) {
        var options = structure.options();
        if (options == null) return List.of();
        List<UiNode> content = new ArrayList<>();
        content.add(heading(text("conditions.assumptions"), Heading.SECTION, ScenarioLabel.definition(options, sceneKey)));
        content.add(new UiNode.Gap(4));
        for (var condition : ScenarioLabel.positiveAssumptions(options, sceneKey))
            addCondition(content, condition, ITEM_INDENT, 0, false);
        List<LootConditionInfo> negatives = ScenarioLabel.negativeConditions(options, sceneKey);
        if (!negatives.isEmpty()) {
            int parent = content.size();
            content.add(action(showOther ? "conditions.hide_other" : "conditions.show_other", ScenarioUi.Icon.DOWN, toggleOther));
            if (showOther || ScenarioLabel.isBaseline(sceneKey))
                for (var condition : negatives) addCondition(content, condition, ITEM_INDENT, parent, false);
        }
        if (ScenarioLabel.positiveAssumptions(options, sceneKey).isEmpty() && negatives.isEmpty())
            content.add(new UiNode.Row(text("no_assumptions"), UiTextPalette.Parchment.LABEL));
        content.add(new UiNode.Gap(9));
        content.add(heading(text("conditions.branches"), Heading.SECTION, List.of(text("conditions.candidates_hint"))));
        content.add(new UiNode.Row(4, UiNode.NO_PARENT, text("conditions.candidates_hint"), UiTextPalette.Parchment.HINT,
                List.of(), List.of(), null, null));
        Map<Group, List<ScenarioBranch>> groups = new LinkedHashMap<>();
        for (var branch : structure.branches()) {
            if (branch.injectionSource().isEmpty() && branch.conditions().isEmpty()
                    && branch.requirements().isEmpty() && branch.luck().isTrivial()) continue;
            var group = new Group(branch.conditions(), branch.requirements(), branch.luck(), branch.activeScenes(),
                    branch.uncertain(), branch.injectionSource(), branch.injectionMode());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(branch);
        }
        int ordinal = 0;
        for (var entry : groups.entrySet()) {
            if (!entry.getKey().source().isEmpty()) continue;
            addBranch(content, structure, sceneKey, ++ordinal, entry.getKey(), entry.getValue(), visible, locate, recommend);
        }
        if (ordinal == 0) content.add(new UiNode.Row(text("conditions.no_branches"), UiTextPalette.Parchment.LABEL));
        boolean hasInjection = groups.keySet().stream().anyMatch(group -> !group.source().isEmpty());
        if (hasInjection) {
            content.add(new UiNode.Gap(12));
            content.add(heading(text("injection.title"), Heading.SECTION, List.of(text("injection.limit"))));
            content.add(new UiNode.Row(text("injection.limit"), UiTextPalette.Parchment.HINT));
            for (var entry : groups.entrySet()) {
                if (entry.getKey().source().isEmpty()) continue;
                addBranch(content, structure, sceneKey, ++ordinal, entry.getKey(), entry.getValue(), visible, locate, recommend);
            }
        }
        return List.copyOf(content);
    }

    private static void addBranch(List<UiNode> nodes, CatalogTableDto table, String scene, int ordinal, Group group,
                                  List<ScenarioBranch> branches, BiPredicate<String, String> visible,
                                  BiConsumer<String, String> locate, Consumer<ResourceLocation> recommend) {
        nodes.add(new UiNode.Gap(8));
        int parent = nodes.size();
        boolean active = group.scenes().contains(scene);
        Component title = group.source().equals("observed") ? text("injection.observed")
                : !group.source().isEmpty() ? text("injection.known", group.source()) : text("conditions.branch", ordinal);
        nodes.add(heading(title, Heading.ENTRY, List.of(title)));
        for (var condition : group.conditions()) addCondition(nodes, condition, ITEM_INDENT, parent, false);
        if (group.conditions().isEmpty()) nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                text("conditions.unconditional"), UiTextPalette.Parchment.LABEL, List.of(), List.of(), null, null));
        if (group.uncertain()) nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                text(group.source().equals("observed") ? "injection.observed_hint" : "conditions.hypothetical"),
                UiTextPalette.Parchment.NEGATIVE, List.of(), List.of(), null, null));
        if (!group.extras().isEmpty() || !group.luck().isTrivial()) {
            int extraParent = nodes.size();
            nodes.add(new UiNode.Row(ITEM_INDENT, parent, text("conditions.additional"), UiTextPalette.Parchment.LABEL,
                    List.of(), List.of(), null, null));
            for (var condition : group.extras()) addCondition(nodes, condition, ITEM_INDENT + INDENT_STEP, extraParent, true);
            LuckGate luck = group.luck();
            if (luck.minLuck().isPresent()) nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                    text("conditions.luck_gate", String.format(Locale.ROOT, "%.2f", luck.minLuck().getAsDouble()))
                            .withStyle(style -> style.withColor(UiTextPalette.Parchment.ACCENT)),
                    UiTextPalette.Parchment.LABEL, List.of(), List.of(), null, null));
            if (luck.rangeLimited()) nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                    text("conditions.luck_range"), UiTextPalette.Parchment.LABEL, List.of(), List.of(), null, null));
            if (luck.bonusRollsGate().isPresent()) nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                    text("conditions.bonus_luck", String.format(Locale.ROOT, "%.2f", luck.bonusRollsGate().getAsDouble())),
                    UiTextPalette.Parchment.LABEL, List.of(), List.of(), null, null));
        }
        if (!group.mode().isEmpty()) nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                text("injection." + group.mode()), UiTextPalette.Parchment.LABEL, List.of(), List.of(), null, null));
        if (!active) {
            nodes.add(new UiNode.Row(ITEM_INDENT, parent, text("conditions.inactive"), UiTextPalette.Parchment.NEGATIVE,
                    List.of(), List.of(), null, null));
            return;
        }
        Set<String> seen = new HashSet<>();
        List<UiNode.InlineIcon> icons = new ArrayList<>();
        for (var branch : branches) {
            if (!seen.add(branch.kind() + "#" + branch.target())) continue;
            boolean revealed = visible.test(branch.kind(), branch.target());
            UiIcon icon = UNKNOWN;
            List<Component> tooltip = List.of(text("conditions.undiscovered"));
            UiAction action = null;
            if (revealed) {
                Component name;
                if (branch.kind().equals("item")) {
                    var signature = LootResultSignature.fromStoredKey(branch.target());
                    if (signature == null) continue;
                    icon = new UiIcon.Item(signature.createPreviewStack());
                    name = table.items().stream().filter(item -> item.signature().equals(signature))
                            .map(CatalogTableDto.ItemEntry::displayName).findFirst().orElse(Component.literal(signature.itemId().toString()));
                } else {
                    icon = new UiIcon.Item(new ItemStack(branch.kind().equals("table") ? Items.BOOK : Items.NAME_TAG));
                    var child = ScenarioSimulationClientState.table(ResourceLocation.tryParse(branch.target()));
                    name = branch.kind().equals("table") && child != null ? child.displayName() : Component.literal(branch.target());
                }
                tooltip = List.of(text("conditions.target." + branch.kind(), name), text("conditions.candidates_hint"), text("conditions.locate"));
                action = () -> locate.accept(branch.kind(), branch.target());
            }
            icons.add(new UiNode.InlineIcon(icon, tooltip, branch, action));
            if (icons.size() == 4) {
                nodes.add(new UiNode.Row(ITEM_INDENT, parent, ScenarioLabel.symbol("→"), UiTextPalette.Parchment.ACCENT,
                        List.copyOf(icons), List.of(text("conditions.candidates_hint")), null, null));
                icons.clear();
            }
        }
        if (!icons.isEmpty()) nodes.add(new UiNode.Row(ITEM_INDENT, parent, ScenarioLabel.symbol("→"), UiTextPalette.Parchment.ACCENT,
                List.copyOf(icons), List.of(text("conditions.candidates_hint")), null, null));
        for (var branch : branches) if (branch.kind().equals("table") && visible.test(branch.kind(), branch.target())) {
            nodes.add(action("recommend.title", ScenarioUi.Icon.RECOMMEND, () -> recommend.accept(ResourceLocation.parse(branch.target()))));
            break;
        }
    }

    private static UiNode.Row heading(Component value, Heading kind, List<Component> tooltip) {
        return new UiNode.Row(4, UiNode.NO_PARENT, value.copy().withStyle(ChatFormatting.BOLD),
                UiTextPalette.Parchment.TITLE, List.of(), tooltip, kind, null);
    }
    private static UiNode.Row action(String key, ScenarioUi.Icon icon, UiAction action) {
        Component label = text(key);
        return new UiNode.Row(4, UiNode.NO_PARENT, new UiNode.InlineIcon(ScenarioUi.icon(icon), List.of(label), null, action),
                label.copy().withStyle(ChatFormatting.UNDERLINE), UiTextPalette.Parchment.BODY, List.of(), List.of(label), null, action);
    }
    private static void addCondition(List<UiNode> nodes, LootConditionInfo condition, int indent, int parent, boolean secondary) {
        int self = nodes.size();
        boolean uncertain = condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY);
        Component styled = ScenarioLabel.conditionText(condition).copy();
        if (secondary) styled = styled.copy().withStyle(style -> style.withColor(UiTextPalette.Parchment.LABEL));
        String type = condition.conditionType().toString();
        if (type.equals("minecraft:all_of")) styled = ScenarioLabel.symbol("∧ ").copy().append(styled);
        if (type.equals("minecraft:any_of")) styled = ScenarioLabel.symbol("∨ ").copy().append(styled);
        if (type.equals("minecraft:inverted")) styled = ScenarioLabel.symbol("¬ ").copy().append(text("conditions.negation"));
        if (condition.conditionType().getPath().contains("random_chance") || condition.conditionType().getPath().equals("table_bonus"))
            styled = Component.empty().append(text("conditions.random_trigger").withStyle(style -> style.withColor(UiTextPalette.Parchment.ACCENT)))
                    .append(" · ").append(styled);
        if (uncertain) styled = styled.copy().append(" · ").append(text("conditions." +
                condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY)).withStyle(style -> style.withColor(UiTextPalette.Parchment.NEGATIVE)));
        nodes.add(new UiNode.Row(indent, parent, styled, UiTextPalette.Parchment.BODY, List.of(),
                List.of(condition.description(), Component.literal(condition.conditionType().toString())), null, null));
        for (var child : condition.children()) addCondition(nodes, child, indent + INDENT_STEP, self, secondary);
    }
    private static net.minecraft.network.chat.MutableComponent text(String key, Object... args) {
        return ScenarioSimulationClientState.text(key, args).copy();
    }
}
