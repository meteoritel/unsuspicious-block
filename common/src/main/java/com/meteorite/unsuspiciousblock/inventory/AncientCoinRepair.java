package com.meteorite.unsuspiciousblock.inventory;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class AncientCoinRepair {
    private static final int REPAIR_DIVISOR = 4;

    private AncientCoinRepair() {
    }

    public record Result(ItemStack output, int experienceCost, int materialCost) {
    }

    public static @Nullable Result calculate(ItemStack left, ItemStack right, @Nullable String name) {
        if (!right.is(ModItems.ANCIENT_COIN) || !left.isDamageableItem() || left.getDamageValue() <= 0) {
            return null;
        }

        ItemStack output = left.copy();
        int repairPerCoin = Math.max(1, output.getMaxDamage() / REPAIR_DIVISOR);
        int remaining = output.getDamageValue();
        int coinsUsed = 0;
        while (remaining > 0 && coinsUsed < right.getCount()) {
            remaining -= repairPerCoin;
            coinsUsed++;
        }
        output.setDamageValue(Math.max(0, remaining));

        // 非修复材料使原版提前返回，宿主需在这里补齐原版命名语义。
        int renameCost = 0;
        if (name != null && !StringUtil.isBlank(name)) {
            if (!name.equals(left.getHoverName().getString())) {
                output.set(DataComponents.CUSTOM_NAME, Component.literal(name));
                renameCost = 1;
            }
        } else if (name != null && left.has(DataComponents.CUSTOM_NAME)) {
            output.remove(DataComponents.CUSTOM_NAME);
            renameCost = 1;
        }

        int baseCost = left.getOrDefault(DataComponents.REPAIR_COST, 0)
                + right.getOrDefault(DataComponents.REPAIR_COST, 0);
        return new Result(output, baseCost + coinsUsed + renameCost, coinsUsed);
    }
}
