package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
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
 * 注意：本物品目前处于未完成（WIP）阶段，被动能力尚未实现。
 * 恩惠值的权威数据存储在玩家身上，tooltip 读取客户端同步缓存进行展示。
 */
public class HandOfCatItem extends Item {

    public HandOfCatItem(Properties properties) {
        super(properties);
    }

    // 物品名称追加 [WIP] 后缀，标注功能未完成
    @Override
    public @NotNull Component getName(@NotNull ItemStack stack) {
        MutableComponent base = Component.translatable(this.getDescriptionId(stack));
        return base.append(Component.translatable("item.unsuspiciousblock.hand_of_cat.wip_suffix")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        // 恩惠值（读取客户端同步缓存）
        int favor = HandOfCatClientState.getCachedFavor();
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_favor",
                favor, CatFavorState.MAX_FAVOR).withStyle(ChatFormatting.GOLD));

        // 开发中提示
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip_wip")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

        super.appendHoverText(stack, context, tooltipLines, flag);
    }
}
