package com.meteorite.unsuspiciousblock.platform.plugin.mekanism;

import com.meteorite.unsuspiciousblock.core.pottery.PotteryFiring;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.MekanismRecipeTypes;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Stream;

/** 将共用制陶产物规则接入 Mekanism 的原生熔炼类型。 */
@SuppressWarnings("unused") // 由可选联动入口反射加载。
public final class MekanismPotterySmeltingRecipe extends ItemStackToItemStackRecipe {
    private static final MapCodec<MekanismPotterySmeltingRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    ItemStackIngredient.CODEC.fieldOf("input").forGetter(MekanismPotterySmeltingRecipe::getInput),
                    PotteryFiring.CODEC.fieldOf("product").forGetter(recipe -> recipe.product)
            ).apply(instance, MekanismPotterySmeltingRecipe::new));
    private static final StreamCodec<RegistryFriendlyByteBuf, MekanismPotterySmeltingRecipe> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStackIngredient.STREAM_CODEC, MekanismPotterySmeltingRecipe::getInput,
                    ByteBufCodecs.fromCodec(PotteryFiring.CODEC), recipe -> recipe.product,
                    MekanismPotterySmeltingRecipe::new);
    private static final RecipeSerializer<MekanismPotterySmeltingRecipe> SERIALIZER = new RecipeSerializer<>() {
        @Override
        public @NotNull MapCodec<MekanismPotterySmeltingRecipe> codec() {
            return CODEC;
        }

        @Override
        public @NotNull StreamCodec<RegistryFriendlyByteBuf, MekanismPotterySmeltingRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    };

    private final ItemStackIngredient input;
    private final PotteryFiring product;

    private MekanismPotterySmeltingRecipe(ItemStackIngredient input, PotteryFiring product) {
        super(MekanismRecipeTypes.TYPE_SMELTING.value());
        this.input = input;
        this.product = product;
    }

    /** 供 NeoForge 的可选联动入口注册本配方的序列化器。 */
    public static RecipeSerializer<MekanismPotterySmeltingRecipe> createSerializer() {
        return SERIALIZER;
    }

    @Override
    public boolean test(@NotNull ItemStack stack) {
        return input.test(stack);
    }

    @Override
    public @NotNull ItemStackIngredient getInput() {
        return input;
    }

    /** 机器传入实际消耗的坯件，返回按其纹饰计算的独立产物栈。 */
    @Override
    public @NotNull ItemStack getOutput(@NotNull ItemStack stack) {
        return product.fire(stack);
    }

    @Override
    public @NotNull ItemStack getResultItem(HolderLookup.@NotNull Provider registries) {
        return ItemStack.EMPTY;
    }

    /** 为 Mekanism 的配方查看器提供兜底成品和当前陶片标签中的纹样。 */
    @Override
    public @NotNull List<ItemStack> getOutputDefinition() {
        ItemStack unpatterned = product.fire(ItemStack.EMPTY);
        if (product == PotteryFiring.POT) {
            return List.of(unpatterned);
        }
        Stream<ItemStack> sherds = BuiltInRegistries.ITEM.getTag(ItemTags.DECORATED_POT_SHERDS).stream()
                .flatMap(HolderSet.Named::stream)
                .map(pattern -> pattern.value().getDefaultInstance());
        return Stream.concat(Stream.of(unpatterned), sherds).toList();
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
