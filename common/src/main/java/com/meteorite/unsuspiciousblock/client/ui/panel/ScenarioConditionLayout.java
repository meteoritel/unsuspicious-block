package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 条件树的局部折行：逻辑节点映射到首个视觉行，续行不重复连线和动作。 */
final class ScenarioConditionLayout {
    private ScenarioConditionLayout() {}

    static List<UiNode> wrap(List<UiNode> source, Font font, int width) {
        List<UiNode> output = new ArrayList<>();
        int[] firstRows = new int[source.size()];
        boolean[] hasChildren = new boolean[source.size()];
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index) instanceof UiNode.Row row && row.parentRow() >= 0 && row.parentRow() < index) {
                hasChildren[row.parentRow()] = true;
            }
        }
        for (int index = 0; index < source.size(); index++) {
            firstRows[index] = output.size();
            if (!(source.get(index) instanceof UiNode.Row row)) {
                output.add(source.get(index));
                continue;
            }
            int indent = Math.clamp(width - 60, 0, row.indent());
            int leading = row.leading() == null ? 0 : row.leading().icon().width() + 3;
            int trailing = row.icons().stream().mapToInt(icon -> icon.icon().width() + 3).sum();
            // 父行的续行稍向右缩进，给从首行延伸到子节点的树枝留出空隙。
            int continuationIndent = leading + (hasChildren[index] ? 4 : 0);
            int budget = Math.max(12, width - indent - continuationIndent - trailing - 4);
            var lines = font.getSplitter().splitLines(row.text(), budget, Style.EMPTY);
            int parent = row.parentRow() >= 0 && row.parentRow() < index
                    ? firstRows[row.parentRow()] : UiNode.NO_PARENT;
            for (int line = 0; line < lines.size(); line++) {
                var text = Component.empty();
                lines.get(line).visit((style, value) -> {
                    text.append(Component.literal(value).setStyle(style));
                    return Optional.empty();
                }, Style.EMPTY);
                output.add(new UiNode.Row(indent + (line == 0 ? 0 : continuationIndent),
                        line == 0 ? parent : UiNode.NO_PARENT, line == 0 ? row.leading() : null,
                        text, row.color(), line == 0 ? row.icons() : List.of(), row.tooltip(),
                        row.payload(), line == 0 ? row.action() : null));
            }
            if (lines.isEmpty()) output.add(row);
        }
        return List.copyOf(output);
    }
}
