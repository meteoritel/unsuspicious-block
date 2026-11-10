package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.core.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxMenu;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.List;
import java.util.function.Supplier;

/** Menu 与客户端 Screen 的对应清单。 */
public final class ModMenuScreens {
    /** Menu 类型延迟到平台注册阶段解析，避免提前读取回写字段。 */
    public record ScreenEntry<T extends AbstractContainerMenu>(
            Supplier<MenuType<T>> menuType,
            ScreenFactory<T> screen) {
    }

    public static final List<ScreenEntry<?>> MANIFEST = List.of(
            new ScreenEntry<>(() -> SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new),
            new ScreenEntry<>(() -> PotteryWheelMenu.TYPE, PotteryWheelScreen::new)
    );

    private ModMenuScreens() {
    }

    /** 在平台 Screen 注册事件中解析 Menu 类型并交付工厂。 */
    public static void forEach(ScreenRegistrar registrar) {
        for (ScreenEntry<?> entry : MANIFEST) {
            register(entry, registrar);
        }
    }

    private static <T extends AbstractContainerMenu> void register(ScreenEntry<T> entry, ScreenRegistrar registrar) {
        registrar.register(entry.menuType().get(), entry.screen());
    }

    /** 保持 Screen 与其 Menu 类型一致的构造契约。 */
    @FunctionalInterface
    public interface ScreenFactory<T extends AbstractContainerMenu> {
        /** 使用平台提供的 Menu、玩家背包和标题构造界面。 */
        AbstractContainerScreen<T> create(T menu, Inventory inventory, Component title);
    }

    /** 适配不同平台的泛型 Screen 注册 API。 */
    @FunctionalInterface
    public interface ScreenRegistrar {
        /** 为 Menu 类型登记匹配的 Screen 工厂。 */
        <T extends AbstractContainerMenu> void register(
                MenuType<T> menuType,
                ScreenFactory<T> screen);
    }
}
