package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.cat.CatFavorAbility;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 猫之手——来自古老猫国度的契约。持有者可累积「猫之恩惠」并获得猫王国的庇护。
 * 恩惠值的权威数据存储在玩家身上，tooltip 读取客户端同步缓存进行展示。
 * 默认仅显示简要信息（恩惠值/命数），按住 Shift 时展开各被动能力的详细作用。
 */
public class HandOfCatItem extends Item {

    public HandOfCatItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        int favor = HandOfCatClientState.getCachedFavor();
        int lives = HandOfCatClientState.getCachedNineLivesCount();

        // 简要信息：恩惠值
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_favor",
                favor, CatFavorAbility.NINE_LIVES.threshold()).withStyle(ChatFormatting.GOLD));

        // 简要信息：残存命数（>0 时显示）
        if (lives > 0) {
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_lives",
                    lives).withStyle(ChatFormatting.AQUA));
        }

        if (Screen.hasShiftDown()) {
            // 详尽模式：遍历枚举列出全部能力 + 描述
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_abilities")
                    .withStyle(ChatFormatting.GRAY));
            for (CatFavorAbility ability : CatFavorAbility.values()) {
                appendAbility(tooltipLines, ability, favor);
            }
        } else {
            // 简要模式：仅提示按住 Shift 查看详情
            tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_detail_hint")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        super.appendHoverText(stack, context, tooltipLines, flag);
    }

    // 追加一条能力状态：已解锁显示绿色 + 描述，未解锁显示灰色并标注所需恩惠
    private static void appendAbility(List<Component> lines, CatFavorAbility ability, int favor) {
        boolean unlocked = ability.isUnlockedAt(favor);
        MutableComponent name = Component.translatable(ability.nameKey());
        if (unlocked) {
            lines.add(Component.literal(" ✔ ").withStyle(ChatFormatting.GREEN)
                    .append(name.withStyle(ChatFormatting.GREEN)));
            lines.add(Component.literal("    ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.translatable(ability.descKey()).withStyle(ChatFormatting.DARK_GRAY)));
        } else {
            lines.add(Component.literal(" ✖ ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(name.withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.required", ability.threshold())
                            .withStyle(ChatFormatting.DARK_GRAY)));
        }
    }
}
