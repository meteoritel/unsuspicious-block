package com.meteorite.unsuspiciousblock.core.potion;

import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 灵猫药水跨平台酿造中枢——统一维护自定义药水物品的酿造台输入校验、材料判定与产物映射。
 *
 * <p>支持完整酿造链：
 * <ul>
 *   <li>粗制药水（普通 / 喷溅 / 滞留）+ 咕噜荆芥 → 对应形态基础灵猫药水</li>
 *   <li>任意形态基础灵猫药水 + 红石粉 → 同形态延长版（8:00）</li>
 *   <li>任意形态基础灵猫药水 + 萤石粉 → 同形态强化版（喵步 II，1:30）</li>
 *   <li>普通灵猫药水 + 火药 → 喷溅型灵猫药水（保留当前变体）</li>
 *   <li>喷溅型灵猫药水 + 龙息 → 滞留型灵猫药水（保留当前变体）</li>
 * </ul>
 */
public final class PurrmintPotionBrewing {

    private PurrmintPotionBrewing() {
    }

    /** 判断物品是否为任意形态的灵猫药水（供酿造台槽位与漏斗输入放行）。 */
    public static boolean isPurrmintPotionItem(ItemStack stack) {
        if (stack.isEmpty() || ModItems.PURRMINT_POTION == null) {
            return false;
        }
        return stack.is(ModItems.PURRMINT_POTION)
                || stack.is(ModItems.SPLASH_PURRMINT_POTION)
                || stack.is(ModItems.LINGERING_PURRMINT_POTION);
    }

    /** 判断物品是否可作为灵猫药水酿造链的底部输入容器（含粗制药水与三款灵猫药水）。 */
    public static boolean isBrewingInput(ItemStack input) {
        return isPurrmintPotionItem(input) || isAwkwardVanillaPotion(input);
    }

    /** 判断物品是否为灵猫药水酿造链的顶部原料。 */
    public static boolean isBrewingIngredient(ItemStack ingredient) {
        if (ingredient.isEmpty() || ModItems.PURRMINT == null) {
            return false;
        }
        return ingredient.is(ModItems.PURRMINT)
                || ingredient.is(Items.REDSTONE)
                || ingredient.is(Items.GLOWSTONE_DUST)
                || ingredient.is(Items.GUNPOWDER)
                || ingredient.is(Items.DRAGON_BREATH);
    }

    /** 判断指定底部容器与顶部原料能否触发灵猫药水酿造。 */
    public static boolean hasMix(ItemStack input, ItemStack ingredient) {
        return !getOutput(input, ingredient).isEmpty();
    }

    /**
     * 计算指定底部容器与顶部原料的酿造产物。
     *
     * @param input      底部药水槽物品
     * @param ingredient 顶部原料槽物品
     * @return 命中配方时返回新产物栈，否则返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack getOutput(ItemStack input, ItemStack ingredient) {
        if (input.isEmpty() || ingredient.isEmpty()
                || ModItems.PURRMINT == null
                || ModItems.PURRMINT_POTION == null
                || ModPotions.PURRMINT == null) {
            return ItemStack.EMPTY;
        }

        // 1. 粗制药水（普通 / 喷溅 / 滞留）+ 咕噜荆芥 → 对应形态的基础灵猫药水
        if (ingredient.is(ModItems.PURRMINT) && isAwkwardVanillaPotion(input)) {
            Item target = mapAwkwardToPurrmintItem(input);
            if (target != null) {
                return createStack(target, ModPotions.PURRMINT);
            }
        }

        if (!isPurrmintPotionItem(input)) {
            return ItemStack.EMPTY;
        }

        PotionContents contents = input.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);

        // 2. 基础灵猫药水 + 红石粉 → 延长版（仅基础版可延长）
        if (ingredient.is(Items.REDSTONE) && contents.is(ModPotions.PURRMINT)) {
            return createStack(input.getItem(), ModPotions.LONG_PURRMINT);
        }

        // 3. 基础灵猫药水 + 萤石粉 → 强化版（仅基础版可强化）
        if (ingredient.is(Items.GLOWSTONE_DUST) && contents.is(ModPotions.PURRMINT)) {
            return createStack(input.getItem(), ModPotions.STRONG_PURRMINT);
        }

        // 4. 普通灵猫药水 + 火药 → 喷溅型（保留当前变体）
        if (ingredient.is(Items.GUNPOWDER) && input.is(ModItems.PURRMINT_POTION)) {
            return copyWithItem(input, ModItems.SPLASH_PURRMINT_POTION);
        }

        // 5. 喷溅型灵猫药水 + 龙息 → 滞留型（保留当前变体）
        if (ingredient.is(Items.DRAGON_BREATH) && input.is(ModItems.SPLASH_PURRMINT_POTION)) {
            return copyWithItem(input, ModItems.LINGERING_PURRMINT_POTION);
        }

        return ItemStack.EMPTY;
    }

    /** 构造携带指定灵猫药水变体的物品栈。 */
    public static ItemStack createStack(Item item, Holder<Potion> potion) {
        return PotionContents.createItemStack(item, potion);
    }

    /** 构建创造模式物品栏展示的全部 9 瓶灵猫药水变体（三形态 × 基础/延长/强化）。 */
    public static List<ItemStack> createCreativeStacks() {
        if (ModItems.PURRMINT_POTION == null || ModPotions.PURRMINT == null) {
            return List.of();
        }
        return List.of(
                createStack(ModItems.PURRMINT_POTION, ModPotions.PURRMINT),
                createStack(ModItems.PURRMINT_POTION, ModPotions.LONG_PURRMINT),
                createStack(ModItems.PURRMINT_POTION, ModPotions.STRONG_PURRMINT),
                createStack(ModItems.SPLASH_PURRMINT_POTION, ModPotions.PURRMINT),
                createStack(ModItems.SPLASH_PURRMINT_POTION, ModPotions.LONG_PURRMINT),
                createStack(ModItems.SPLASH_PURRMINT_POTION, ModPotions.STRONG_PURRMINT),
                createStack(ModItems.LINGERING_PURRMINT_POTION, ModPotions.PURRMINT),
                createStack(ModItems.LINGERING_PURRMINT_POTION, ModPotions.LONG_PURRMINT),
                createStack(ModItems.LINGERING_PURRMINT_POTION, ModPotions.STRONG_PURRMINT)
        );
    }

    private static boolean isAwkwardVanillaPotion(ItemStack stack) {
        if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION) && !stack.is(Items.LINGERING_POTION)) {
            return false;
        }
        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        return contents.is(Potions.AWKWARD);
    }

    private static @Nullable Item mapAwkwardToPurrmintItem(ItemStack awkward) {
        if (awkward.is(Items.POTION)) {
            return ModItems.PURRMINT_POTION;
        }
        if (awkward.is(Items.SPLASH_POTION)) {
            return ModItems.SPLASH_PURRMINT_POTION;
        }
        if (awkward.is(Items.LINGERING_POTION)) {
            return ModItems.LINGERING_PURRMINT_POTION;
        }
        return null;
    }

    private static ItemStack copyWithItem(ItemStack source, Item targetItem) {
        ItemStack result = new ItemStack(targetItem);
        PotionContents contents = source.get(DataComponents.POTION_CONTENTS);
        if (contents != null) {
            result.set(DataComponents.POTION_CONTENTS, contents);
        }
        return result;
    }
}
