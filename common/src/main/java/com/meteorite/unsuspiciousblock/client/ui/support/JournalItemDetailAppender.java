package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 为考古笔记提取可区分物品变体的精简 tooltip 信息。
 */
public final class JournalItemDetailAppender {

    private JournalItemDetailAppender() {
    }

    // 仅追加影响物品身份辨识的信息，避免恢复原版 tooltip 中的属性、耐久等冗余内容
    public static void append(List<Component> lines, ItemStack stack) {
        Item.TooltipContext context = Item.TooltipContext.of(Minecraft.getInstance().level);
        appendPotionEffects(lines, stack, context);
        appendJukeboxSong(lines, stack, context);
        appendInstrument(lines, stack);
        appendEnchantments(lines, stack, context);
    }

    // 复用原版效果文本格式，但截断其后附带的属性修饰信息
    private static void appendPotionEffects(List<Component> lines, ItemStack stack,
                                            Item.TooltipContext context) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || !contents.hasEffects()) {
            return;
        }

        List<Component> potionLines = new ArrayList<>();
        contents.addPotionTooltip(potionLines::add, potionDurationFactor(stack), context.tickRate());
        for (Component line : potionLines) {
            if (line.getString().isEmpty()) {
                break;
            }
            lines.add(line);
        }
    }

    // 原版滞留药水与药箭展示的是实际生效时长，需使用对应的持续时间倍率
    private static float potionDurationFactor(ItemStack stack) {
        if (stack.getItem() instanceof LingeringPotionItem) {
            return 0.25F;
        }
        if (stack.getItem() instanceof TippedArrowItem) {
            return 0.125F;
        }
        return 1.0F;
    }

    private static void appendJukeboxSong(List<Component> lines, ItemStack stack,
                                          Item.TooltipContext context) {
        JukeboxPlayable playable = stack.get(DataComponents.JUKEBOX_PLAYABLE);
        if (playable != null) {
            playable.addToTooltip(context, lines::add, TooltipFlag.NORMAL);
        }
    }

    private static void appendInstrument(List<Component> lines, ItemStack stack) {
        Holder<Instrument> instrument = stack.get(DataComponents.INSTRUMENT);
        if (instrument == null) {
            return;
        }
        instrument.unwrapKey()
                .map(ResourceKey::location)
                .map(id -> Component.translatable(Util.makeDescriptionId("instrument", id))
                        .withStyle(ChatFormatting.GRAY))
                .ifPresent(lines::add);
    }

    private static void appendEnchantments(List<Component> lines, ItemStack stack,
                                            Item.TooltipContext context) {
        appendEnchantments(lines, stack.get(DataComponents.STORED_ENCHANTMENTS), context);
        appendEnchantments(lines, stack.get(DataComponents.ENCHANTMENTS), context);
    }

    private static void appendEnchantments(List<Component> lines, ItemEnchantments enchantments,
                                            Item.TooltipContext context) {
        if (enchantments != null && !enchantments.isEmpty()) {
            enchantments.addToTooltip(context, lines::add, TooltipFlag.NORMAL);
        }
    }
}
