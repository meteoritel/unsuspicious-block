package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/***
 * 猫之瞳——来自深埋的宝藏。携带时可揭示附魔台与铁砧/砂轮的隐藏信息。
 * 默认仅显示一句暗示，按住 Shift 时列出持有生效的能力名。
 */
public class EyeOfCatItem extends Item {

    public EyeOfCatItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder tooltip = new TooltipBuilder(tooltipLines);

        // 一句话暗示
        tooltip.intro("item.unsuspiciousblock.eye_of_cat.tooltip.hint");

        // 详尽模式：列出能力名；否则显示通用 Shift 展开提示
        tooltip.expandable(t -> {
            t.section("item.unsuspiciousblock.eye_of_cat.tooltip.abilities");
            t.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip.enchant_reveal")
                    .withStyle(TooltipBuilder.POSITIVE));
            t.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip.anvil_breakdown")
                    .withStyle(TooltipBuilder.POSITIVE));
            t.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip.grindstone_breakdown")
                    .withStyle(TooltipBuilder.POSITIVE));
        });
    }
}
