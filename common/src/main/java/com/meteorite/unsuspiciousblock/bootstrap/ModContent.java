package com.meteorite.unsuspiciousblock.bootstrap;

import com.meteorite.unsuspiciousblock.block.BlockRegistrar;
import com.meteorite.unsuspiciousblock.block.ModBlocks;
import com.meteorite.unsuspiciousblock.blockentity.BlockEntityRegistrar;
import com.meteorite.unsuspiciousblock.blockentity.ModBlockEntities;
import com.meteorite.unsuspiciousblock.effect.MobEffectRegistrar;
import com.meteorite.unsuspiciousblock.effect.ModEffects;
import com.meteorite.unsuspiciousblock.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.item.ItemRegistrar;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.recipe.ModRecipeSerializers;
import com.meteorite.unsuspiciousblock.sound.ModSounds;
import com.meteorite.unsuspiciousblock.sound.SoundRegistrar;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.world.ModFeatures;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class ModContent {
    private static Supplier<MenuType<SpecimenBoxMenu>> specimenBox;
    private static Supplier<MenuType<PotteryWheelMenu>> potteryWheel;

    private ModContent() {
    }

    @FunctionalInterface
    public interface MenuRegistrar {
        <T extends AbstractContainerMenu> Supplier<MenuType<T>> register(
                String name, BiFunction<Integer, Inventory, T> factory);
    }

    public record Registrars(
            BlockRegistrar blocks,
            BlockEntityRegistrar blockEntities,
            ItemRegistrar items,
            ModRecipeSerializers.SerializerRegistrar recipes,
            EntityRegistrar entities,
            MobEffectRegistrar effects,
            SoundRegistrar sounds,
            BiConsumer<String, Supplier<Feature<?>>> features,
            MenuRegistrar menus,
            BiConsumer<String, Supplier<CreativeModeTab>> creativeTabs,
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
        specimenBox = registrars.menus().register(SpecimenBoxMenu.ID.getPath(),
                SpecimenBoxMenu::new);
        potteryWheel = registrars.menus().register(PotteryWheelMenu.ID.getPath(),
                PotteryWheelMenu::new);
        registrars.creativeTabs().accept("main", () -> registrars.creativeTabBuilder().get()
                .title(Component.translatable("itemGroup.unsuspiciousblock.main"))
                .icon(ModItems.CREATIVE_TAB_ICON)
                .displayItems((parameters, output) -> {
                    for (var item : ModItems.CREATIVE_TAB_ITEMS) {
                        output.accept(item.get());
                    }
                })
                .build());
    }

    public static void syncMenuTypes() {
        SpecimenBoxMenu.TYPE = specimenBoxMenu();
        PotteryWheelMenu.TYPE = potteryWheelMenu();
    }

    public static MenuType<SpecimenBoxMenu> specimenBoxMenu() {
        return specimenBox.get();
    }

    public static MenuType<PotteryWheelMenu> potteryWheelMenu() {
        return potteryWheel.get();
    }
}
