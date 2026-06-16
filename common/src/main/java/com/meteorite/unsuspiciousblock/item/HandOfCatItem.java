package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 猫之手——来自古老猫国度的契约。持有者可累积「猫之恩惠」并获得猫王国的庇护。
 * 恩惠值的权威数据存储在玩家身上，tooltip 读取客户端同步缓存进行展示，
 * 并据此显示各被动能力的解锁状态。
 */
public class HandOfCatItem extends Item {

    public HandOfCatItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        int favor = HandOfCatClientState.getCachedFavor();
        // 恩惠值
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_favor",
                favor, CatFavorState.MAX_FAVOR).withStyle(ChatFormatting.GOLD));

        // 能力清单标题
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_abilities")
                .withStyle(ChatFormatting.GRAY));

        // 各被动能力解锁状态（夜视无恩惠门槛，其余按阈值解锁）
        appendAbility(tooltipLines, "item.unsuspiciousblock.hand_of_cat.ability.cat_eye", 0, favor);
        appendAbility(tooltipLines, "item.unsuspiciousblock.hand_of_cat.ability.deterrence",
                CatPassiveAbilities.DETERRENCE_THRESHOLD, favor);
        appendAbility(tooltipLines, "item.unsuspiciousblock.hand_of_cat.ability.step",
                CatPassiveAbilities.STEP_THRESHOLD, favor);
        appendAbility(tooltipLines, "item.unsuspiciousblock.hand_of_cat.ability.soft_paws",
                CatPassiveAbilities.SOFT_PAWS_THRESHOLD, favor);
        appendAbility(tooltipLines, "item.unsuspiciousblock.hand_of_cat.ability.ancient_gift",
                CatPassiveAbilities.ANCIENT_GIFT_THRESHOLD, favor);
        appendAbility(tooltipLines, "item.unsuspiciousblock.hand_of_cat.ability.nine_lives",
                CatPassiveAbilities.NINE_LIVES_THRESHOLD, favor);

        super.appendHoverText(stack, context, tooltipLines, flag);
    }

    // 追加一条能力状态：已解锁显示绿色，未解锁显示灰色并标注所需恩惠
    private static void appendAbility(List<Component> lines, String nameKey, int threshold, int favor) {
        boolean unlocked = favor >= threshold;
        MutableComponent name = Component.translatable(nameKey);
        if (unlocked) {
            lines.add(Component.literal(" ✔ ").withStyle(ChatFormatting.GREEN)
                    .append(name.withStyle(ChatFormatting.GREEN)));
        } else {
            lines.add(Component.literal(" ✖ ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(name.withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.required", threshold)
                            .withStyle(ChatFormatting.DARK_GRAY)));
        }
    }
}
