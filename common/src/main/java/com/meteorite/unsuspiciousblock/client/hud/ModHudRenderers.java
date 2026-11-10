package com.meteorite.unsuspiciousblock.client.hud;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;

/** HUD 绘制入口清单；平台只负责把事件上下文交给清单。 */
public final class ModHudRenderers {
    public static final List<Consumer<GuiGraphics>> MANIFEST = List.of(
            CatFavorHud::render,
            SuspiciousReaderHud::render
    );

    private ModHudRenderers() {
    }

    /** 按固定顺序绘制 HUD；各 HUD 持有自身的显示门禁。 */
    public static void renderAll(GuiGraphics graphics) {
        for (Consumer<GuiGraphics> renderer : MANIFEST) {
            renderer.accept(graphics);
        }
    }
}
