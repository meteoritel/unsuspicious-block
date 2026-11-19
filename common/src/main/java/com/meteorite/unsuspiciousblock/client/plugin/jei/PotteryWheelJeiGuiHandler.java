package com.meteorite.unsuspiciousblock.client.plugin.jei;

import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.screen.PotteryWheelScreen;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.handlers.IScreenHandler;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 仅在陶轮容器可交互时向 JEI 提供并排布局和配方热区。 */
final class PotteryWheelJeiGuiHandler implements IScreenHandler<PotteryWheelScreen>,
        IGuiContainerHandler<PotteryWheelScreen> {
    private static final List<IGuiClickableArea> RECIPE_AREAS = List.of(IGuiClickableArea.createBasic(
            PotteryWheelScreen.PROGRESS_BOUNDS.x(), PotteryWheelScreen.PROGRESS_BOUNDS.y(),
            PotteryWheelScreen.PROGRESS_BOUNDS.width(), PotteryWheelScreen.PROGRESS_BOUNDS.height(),
            PotteryWheelJeiCategory.TYPE));

    @Override
    public @Nullable IGuiProperties apply(PotteryWheelScreen screen) {
        if (screen.isPreviewLightboxOpen() || screen.width <= 0 || screen.height <= 0) return null;
        UiRect bounds = screen.containerBounds();
        return new Properties(bounds.x(), bounds.y(), bounds.width(), bounds.height(), screen.width, screen.height);
    }

    @Override
    public @NotNull List<IGuiClickableArea> getGuiClickableAreas(PotteryWheelScreen screen, double mouseX, double mouseY) {
        return screen.isPreviewLightboxOpen() ? List.of() : RECIPE_AREAS;
    }

    private record Properties(int guiLeft, int guiTop, int guiXSize, int guiYSize, int screenWidth,
                              int screenHeight) implements IGuiProperties {
        @Override
        public @NotNull Class<? extends Screen> screenClass() {
            return PotteryWheelScreen.class;
        }
    }
}
