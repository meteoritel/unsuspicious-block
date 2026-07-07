package com.meteorite.unsuspiciousblock.item;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
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
        // 一句话暗示
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip_hint")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        if (Screen.hasShiftDown()) {
            // 详尽模式：仅列出能力名
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip_abilities")
                    .withStyle(ChatFormatting.GOLD));
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip.enchant_reveal")
                    .withStyle(ChatFormatting.GREEN));
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip.anvil_breakdown")
                    .withStyle(ChatFormatting.GREEN));
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip.grindstone_breakdown")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            // 简要模式：仅提示按住 Shift 查看详情
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.eye_of_cat.tooltip_detail_hint")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        super.appendHoverText(stack, context, tooltipLines, flag);
    }
}
