package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.jetbrains.annotations.Nullable;

/** 本代目录的只读预览上下文；配方和物品修饰器均取服务器当前加载结果。 */
public record LootFunctionPreviewContext(ServerLevel level) {

    // 缺失的引用保持为空，不自行执行或构造替代修饰器。
    @Nullable
    public LootItemFunction modifier(ResourceLocation id) {
        return level.getServer().reloadableRegistries().get().lookup(Registries.ITEM_MODIFIER)
                .flatMap(registry -> registry.get(ResourceKey.create(Registries.ITEM_MODIFIER, id)))
                .map(Holder::value).orElse(null);
    }

    // 与原版 furnace_smelt 使用相同的配方查询和结果读取方式，数量沿用输入栈。
    public ItemStack smelt(ItemStack stack) {
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level)
                .map(recipe -> recipe.value().getResultItem(level.registryAccess()))
                .filter(result -> !result.isEmpty())
                .map(result -> result.copyWithCount(stack.getCount())).orElseGet(stack::copy);
    }
}
