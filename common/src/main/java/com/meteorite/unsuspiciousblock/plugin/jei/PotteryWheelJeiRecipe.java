package com.meteorite.unsuspiciousblock.plugin.jei;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.PotDecorations;

import java.util.Comparator;
import java.util.List;

/** 陶轮台 JEI 展示数据，描述陶罐成型或陶片压印所需的候选材料。 */
public record PotteryWheelJeiRecipe(Mode mode,
                                    List<ItemStack> patternInputs,
                                    List<ItemStack> clayInputs,
                                    ItemStack waterInput,
                                    List<ItemStack> outputs) {
    public PotteryWheelJeiRecipe {
        patternInputs = List.copyOf(patternInputs);
        clayInputs = List.copyOf(clayInputs);
        waterInput = waterInput.copy();
        outputs = List.copyOf(outputs);
    }

    // 创建四面纹饰陶罐的动态展示配方
    public static PotteryWheelJeiRecipe createPotRecipe() {
        List<ItemStack> patterns = getTagStacks(ItemTags.DECORATED_POT_INGREDIENTS);
        return new PotteryWheelJeiRecipe(
                Mode.POT,
                patterns,
                List.of(new ItemStack(Blocks.CLAY), new ItemStack(Items.CLAY_BALL, 4)),
                createWaterBottle(),
                List.of(ModItems.UNFIRED_DECORATED_POT.getDefaultInstance()));
    }

    // 创建陶片压印的动态展示配方
    public static PotteryWheelJeiRecipe createSherdRecipe() {
        List<ItemStack> patterns = getTagStacks(ItemTags.DECORATED_POT_SHERDS);
        return new PotteryWheelJeiRecipe(
                Mode.SHERD,
                patterns,
                List.of(new ItemStack(Blocks.CLAY), Items.CLAY_BALL.getDefaultInstance()),
                createWaterBottle(),
                patterns.stream().map(PotteryWheelJeiRecipe::createUnfiredSherd).toList());
    }

    // 按注册名排序读取 tag，确保每次打开 JEI 时展示顺序稳定
    private static List<ItemStack> getTagStacks(TagKey<Item> tag) {
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> item.builtInRegistryHolder().is(tag))
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .map(Item::getDefaultInstance)
                .toList();
    }

    // 构造原版水瓶，避免把其他药水识别成陶轮台输入
    private static ItemStack createWaterBottle() {
        ItemStack waterBottle = Items.POTION.getDefaultInstance();
        waterBottle.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
        return waterBottle;
    }

    // 为输出索引创建携带对应纹饰组件的未烧制陶片
    private static ItemStack createUnfiredSherd(ItemStack pattern) {
        ItemStack result = ModItems.UNFIRED_DECORATED_SHERD.getDefaultInstance();
        Item item = pattern.getItem();
        result.set(DataComponents.POT_DECORATIONS, new PotDecorations(item, item, item, item));
        return result;
    }

    /** 陶轮台展示模式。 */
    public enum Mode {
        POT,
        SHERD
    }
}
