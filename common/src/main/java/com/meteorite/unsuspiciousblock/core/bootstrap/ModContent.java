package com.meteorite.unsuspiciousblock.core.bootstrap;

import com.meteorite.unsuspiciousblock.core.block.ModBlocks;
import com.meteorite.unsuspiciousblock.core.blockentity.BlockEntityRegistrar;
import com.meteorite.unsuspiciousblock.core.blockentity.ModBlockEntities;
import com.meteorite.unsuspiciousblock.core.effect.ModEffects;
import com.meteorite.unsuspiciousblock.core.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.core.entity.ModEntities;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.core.recipe.ModRecipeSerializers;
import com.meteorite.unsuspiciousblock.core.sound.ModSounds;
import com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.core.world.ModFeatures;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;

import java.util.function.Consumer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.sounds.SoundEvent;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** 跨平台内容声明编排，保存菜单的延迟注册引用。 */
public final class ModContent {
    private static Supplier<MenuType<SpecimenBoxMenu>> specimenBox;
    private static Supplier<MenuType<PotteryWheelMenu>> potteryWheel;

    private ModContent() {
    }

    /** 菜单工厂保留窗口 ID 与 Inventory 的类型关联，回交引用在注册完成后求值。 */
    @FunctionalInterface
    public interface MenuRegistrar {
        <T extends AbstractContainerMenu> void register(
                String name, BiFunction<Integer, Inventory, T> factory, Consumer<Supplier<MenuType<T>>> setter);
    }

    /** 无 common 引用回写的创造标签注册入口。 */
    @FunctionalInterface
    public interface CreativeTabRegistrar {
        void register(String name, Supplier<CreativeModeTab> factory);
    }

    /** 各内容族的平台 adapter；顺序由 register 统一维护。 */
    public record Registrars(
            Registrar<Block, Supplier<Block>> blocks,
            BlockEntityRegistrar blockEntities,
            Registrar<Item, Item> items,
            Registrar<RecipeSerializer<?>, Supplier<RecipeSerializer<?>>> recipes,
            EntityRegistrar entities,
            Registrar<MobEffect, Holder<MobEffect>> effects,
            Registrar<SoundEvent, Holder<SoundEvent>> sounds,
            ModFeatures.FeatureRegistrar features,
            MenuRegistrar menus,
            CreativeTabRegistrar creativeTabs,
            Supplier<CreativeModeTab.Builder> creativeTabBuilder) {
    }

    public static void register(Registrars registrars) {
        ModFeatures.forEach(registrars.features());
        // BlockItem 和方块实体工厂在即时注册平台需要方块实例。
        ModBlocks.forEach(registrars.blocks());
        ModBlockEntities.forEach(registrars.blockEntities());
        ModItems.forEach(registrars.items());
        ModRecipeSerializers.forEach(registrars.recipes());
        ModEntities.forEach(registrars.entities());
        ModEffects.forEach(registrars.effects());
        ModSounds.forEach(registrars.sounds());
        registrars.menus().register(SpecimenBoxMenu.ID.getPath(),
                SpecimenBoxMenu::new, type -> specimenBox = type);
        registrars.menus().register(PotteryWheelMenu.ID.getPath(),
                PotteryWheelMenu::new, type -> potteryWheel = type);
        registrars.creativeTabs().register("main", () -> registrars.creativeTabBuilder().get()
                .title(Component.translatable("itemGroup.unsuspiciousblock.main"))
                .icon(ModItems.CREATIVE_TAB_ICON)
                .displayItems((parameters, output) -> {
                    for (var item : ModItems.CREATIVE_TAB_ITEMS) {
                        output.accept(item.get());
                    }
                })
                .build());
    }

    public static MenuType<SpecimenBoxMenu> specimenBoxMenu() {
        return specimenBox.get();
    }

    public static MenuType<PotteryWheelMenu> potteryWheelMenu() {
        return potteryWheel.get();
    }
}
