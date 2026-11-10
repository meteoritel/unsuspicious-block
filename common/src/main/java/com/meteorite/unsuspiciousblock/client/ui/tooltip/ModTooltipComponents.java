package com.meteorite.unsuspiciousblock.client.ui.tooltip;

import com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/** 自定义 tooltip component 的客户端工厂清单。 */
public final class ModTooltipComponents {
    /** tooltip 数据类型与匹配的客户端绘制工厂。 */
    public record ComponentEntry<T extends TooltipComponent>(
            Class<T> type,
            Function<T, ClientTooltipComponent> factory) {
    }

    public static final List<ComponentEntry<?>> MANIFEST = List.of(
            new ComponentEntry<>(SpecimenBoxTooltip.class, ClientSpecimenBoxTooltip::new)
    );

    private ModTooltipComponents() {
    }

    /** Fabric 回调按类型查找工厂；非本模组数据返回 null，交给其他提供者。 */
    @Nullable
    public static ClientTooltipComponent create(TooltipComponent component) {
        for (ComponentEntry<?> entry : MANIFEST) {
            ClientTooltipComponent result = createIfMatching(entry, component);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Nullable
    private static <T extends TooltipComponent> ClientTooltipComponent createIfMatching(
            ComponentEntry<T> entry, TooltipComponent component) {
        return entry.type().isInstance(component)
                ? entry.factory().apply(entry.type().cast(component))
                : null;
    }
}
