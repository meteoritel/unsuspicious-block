package com.meteorite.unsuspiciousblock.recipe;

import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组配方序列化器清单，由各平台分别完成注册。
 */
public final class ModRecipeSerializers {
    public static Supplier<RecipeSerializer<?>> UNSUSPICIOUS_SAND_SEALING;
    public static Supplier<RecipeSerializer<?>> UNSUSPICIOUS_GRAVEL_SEALING;
    public static Supplier<RecipeSerializer<?>> UNSUSPICIOUS_SAND_CREATION;
    public static Supplier<RecipeSerializer<?>> UNSUSPICIOUS_GRAVEL_CREATION;
    public static Supplier<RecipeSerializer<?>> UNFIRED_DECORATED_SHERD_SMELTING;
    public static Supplier<RecipeSerializer<?>> UNFIRED_DECORATED_POT_SMELTING;

    private ModRecipeSerializers() {
    }

    /** 配方序列化器注册清单条目。 */
    public record SerializerEntry(String name, Supplier<RecipeSerializer<?>> factory,
                                  Consumer<Supplier<RecipeSerializer<?>>> setter) {
    }

    public static final List<SerializerEntry> REGISTRY_MANIFEST = List.of(
            new SerializerEntry("unsuspicious_sand_sealing",
                    () -> createSealing(UnsuspiciousSealingRecipe.Variant.SAND),
                    serializer -> UNSUSPICIOUS_SAND_SEALING = serializer),
            new SerializerEntry("unsuspicious_gravel_sealing",
                    () -> createSealing(UnsuspiciousSealingRecipe.Variant.GRAVEL),
                    serializer -> UNSUSPICIOUS_GRAVEL_SEALING = serializer),
            new SerializerEntry("unsuspicious_sand_creation",
                    () -> createCreation(UnsuspiciousSealingRecipe.Variant.SAND),
                    serializer -> UNSUSPICIOUS_SAND_CREATION = serializer),
            new SerializerEntry("unsuspicious_gravel_creation",
                    () -> createCreation(UnsuspiciousSealingRecipe.Variant.GRAVEL),
                    serializer -> UNSUSPICIOUS_GRAVEL_CREATION = serializer),
            new SerializerEntry("unfired_decorated_sherd_smelting",
                    () -> new net.minecraft.world.item.crafting.SimpleCookingSerializer<>(
                            UnfiredDecoratedSherdSmeltingRecipe::new, 200),
                    serializer -> UNFIRED_DECORATED_SHERD_SMELTING = serializer),
            new SerializerEntry("unfired_decorated_pot_smelting",
                    () -> new net.minecraft.world.item.crafting.SimpleCookingSerializer<>(
                            UnfiredDecoratedPotSmeltingRecipe::new, 200),
                    serializer -> UNFIRED_DECORATED_POT_SMELTING = serializer)
    );

    // 为指定方块类型创建无数据字段的特殊配方序列化器
    private static RecipeSerializer<?> createSealing(UnsuspiciousSealingRecipe.Variant variant) {
        return new SimpleCraftingRecipeSerializer<>((CraftingBookCategory category) ->
                new UnsuspiciousSealingRecipe(category, variant));
    }

    // 为指定方块类型创建空方块制作配方序列化器
    private static RecipeSerializer<?> createCreation(UnsuspiciousSealingRecipe.Variant variant) {
        return new SimpleCraftingRecipeSerializer<>((CraftingBookCategory category) ->
                new UnsuspiciousCreationRecipe(category, variant));
    }

    // 返回配方类型对应的已注册序列化器
    public static RecipeSerializer<?> getSealing(UnsuspiciousSealingRecipe.Variant variant) {
        return switch (variant) {
            case SAND -> UNSUSPICIOUS_SAND_SEALING.get();
            case GRAVEL -> UNSUSPICIOUS_GRAVEL_SEALING.get();
        };
    }

    // 返回空方块制作配方类型对应的已注册序列化器
    public static RecipeSerializer<?> getCreation(UnsuspiciousSealingRecipe.Variant variant) {
        return switch (variant) {
            case SAND -> UNSUSPICIOUS_SAND_CREATION.get();
            case GRAVEL -> UNSUSPICIOUS_GRAVEL_CREATION.get();
        };
    }

    // 遍历清单并交由平台完成注册
    public static void forEach(SerializerRegistrar registrar) {
        for (SerializerEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }

    /** 配方序列化器注册回调。 */
    @FunctionalInterface
    public interface SerializerRegistrar {
        // 注册单个序列化器并回写跨平台引用
        void register(String name, Supplier<RecipeSerializer<?>> factory,
                      Consumer<Supplier<RecipeSerializer<?>>> setter);
    }
}
