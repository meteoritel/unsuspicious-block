package com.meteorite.unsuspiciousblock.recipe;

import com.meteorite.unsuspiciousblock.block.SealedContents;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * 使用沙子或沙砾封存任意单个物品的无序特殊配方。
 */
public class UnsuspiciousSealingRecipe extends CustomRecipe {
    private final Variant variant;

    public UnsuspiciousSealingRecipe(CraftingBookCategory category, Variant variant) {
        super(category);
        this.variant = variant;
    }

    @Override
    public boolean matches(@NotNull CraftingInput input, @NotNull Level level) {
        return findPayload(input) != null;
    }

    @Override
    public @NotNull ItemStack assemble(@NotNull CraftingInput input,
                                       @NotNull HolderLookup.Provider registries) {
        ItemStack payload = findPayload(input);
        if (payload == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = new ItemStack(this.variant.outputItem());
        SealedContents.seal(result, payload);
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.get(this.variant);
    }

    // 查找唯一载荷，同时验证输入中恰好有一个基底和一个载荷
    private ItemStack findPayload(CraftingInput input) {
        boolean foundBase = false;
        ItemStack payload = ItemStack.EMPTY;
        int occupiedSlots = 0;

        for (int index = 0; index < input.size(); index++) {
            ItemStack stack = input.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            occupiedSlots++;
            if (stack.is(this.variant.baseItem()) && !foundBase) {
                foundBase = true;
            } else if (payload.isEmpty() && isAllowedPayload(stack)) {
                payload = stack;
            } else {
                return null;
            }
        }

        return occupiedSlots == 2 && foundBase && !payload.isEmpty() ? payload : null;
    }

    // 排除会导致两种配方冲突的基底，以及可无限嵌套的成品方块
    private static boolean isAllowedPayload(ItemStack stack) {
        return !stack.is(Items.SAND)
                && !stack.is(Items.GRAVEL)
                && !stack.is(ModItems.UNSUSPICIOUS_SAND)
                && !stack.is(ModItems.UNSUSPICIOUS_GRAVEL);
    }

    /** 配方使用的基底与输出类型。 */
    public enum Variant {
        SAND,
        GRAVEL;

        // 获取当前类型使用的原版基底
        public Item baseItem() {
            return this == SAND ? Items.SAND : Items.GRAVEL;
        }

        // 获取当前类型生成的不可疑方块物品
        public Item outputItem() {
            return this == SAND ? ModItems.UNSUSPICIOUS_SAND : ModItems.UNSUSPICIOUS_GRAVEL;
        }
    }
}
