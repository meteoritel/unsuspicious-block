package com.meteorite.unsuspiciousblock.core.recipe;

import com.meteorite.unsuspiciousblock.core.bootstrap.Registrar;

import net.minecraft.world.item.crafting.SimpleCookingSerializer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组配方序列化器清单，由各平台分别完成注册。
 */
public final class ModRecipeSerializers {
    public static Supplier<RecipeSerializer<?>> UNFIRED_DECORATED_SHERD_SMELTING;
    public static Supplier<RecipeSerializer<?>> UNFIRED_DECORATED_POT_SMELTING;

    private ModRecipeSerializers() {
    }

    /** 载体支持的特殊合成种类。 */
    public enum CraftingKind { SEALING, CREATION }

    /** 一个载体与合成种类唯一对应一个 serializer。 */
    public record CraftingKey(UnsuspiciousCarrier carrier, CraftingKind kind) {}

    /** 烧制项不带合成键，保留各自的注册回写。 */
    public record SerializerEntry(String name, Supplier<RecipeSerializer<?>> factory,
                                  Consumer<Supplier<RecipeSerializer<?>>> setter,
                                  @Nullable CraftingKey craftingKey) {}

    private static final Map<CraftingKey, Supplier<RecipeSerializer<?>>> CRAFTING = new HashMap<>();

    public static final List<SerializerEntry> REGISTRY_MANIFEST = List.of(
            crafting("unsuspicious_sand_sealing", UnsuspiciousCarrier.SAND, CraftingKind.SEALING),
            crafting("unsuspicious_gravel_sealing", UnsuspiciousCarrier.GRAVEL, CraftingKind.SEALING),
            crafting("unsuspicious_sand_creation", UnsuspiciousCarrier.SAND, CraftingKind.CREATION),
            crafting("unsuspicious_gravel_creation", UnsuspiciousCarrier.GRAVEL, CraftingKind.CREATION),
            new SerializerEntry("unfired_decorated_sherd_smelting",
                    () -> new SimpleCookingSerializer<>(
                            UnfiredDecoratedSherdSmeltingRecipe::new, 200),
                    serializer -> UNFIRED_DECORATED_SHERD_SMELTING = serializer, null),
            new SerializerEntry("unfired_decorated_pot_smelting",
                    () -> new SimpleCookingSerializer<>(
                            UnfiredDecoratedPotSmeltingRecipe::new, 200),
                    serializer -> UNFIRED_DECORATED_POT_SMELTING = serializer, null)
    );

    static {
        Set<CraftingKey> declared = new HashSet<>();
        for (SerializerEntry entry : REGISTRY_MANIFEST) {
            if (entry.craftingKey() != null && !declared.add(entry.craftingKey())) {
                throw new IllegalStateException("重复的载体配方: " + entry.craftingKey());
            }
        }
        for (UnsuspiciousCarrier carrier : UnsuspiciousCarrier.values()) {
            for (CraftingKind kind : CraftingKind.values()) {
                CraftingKey key = new CraftingKey(carrier, kind);
                if (!declared.contains(key)) throw new IllegalStateException("缺少载体配方: " + key);
            }
        }
    }

    private static SerializerEntry crafting(String name, UnsuspiciousCarrier carrier, CraftingKind kind) {
        CraftingKey key = new CraftingKey(carrier, kind);
        return new SerializerEntry(name, () -> switch (kind) {
            case SEALING -> new SimpleCraftingRecipeSerializer<>((CraftingBookCategory category) ->
                    new UnsuspiciousSealingRecipe(category, carrier));
            case CREATION -> new SimpleCraftingRecipeSerializer<>((CraftingBookCategory category) ->
                    new UnsuspiciousCreationRecipe(category, carrier));
        }, serializer -> CRAFTING.put(key, serializer), key);
    }

    /** 返回当前载体的封存配方序列化器。 */
    public static RecipeSerializer<?> getSealing(UnsuspiciousCarrier carrier) {
        return registered(new CraftingKey(carrier, CraftingKind.SEALING));
    }

    /** 返回当前载体的空方块制作配方序列化器。 */
    public static RecipeSerializer<?> getCreation(UnsuspiciousCarrier carrier) {
        return registered(new CraftingKey(carrier, CraftingKind.CREATION));
    }

    private static RecipeSerializer<?> registered(CraftingKey key) {
        Supplier<RecipeSerializer<?>> serializer = CRAFTING.get(key);
        if (serializer == null) throw new IllegalStateException("载体配方尚未注册: " + key);
        return serializer.get();
    }

    // 遍历清单并交由平台完成注册
    public static void forEach(Registrar<RecipeSerializer<?>, Supplier<RecipeSerializer<?>>> registrar) {
        for (SerializerEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }

}
