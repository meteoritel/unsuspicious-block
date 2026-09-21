package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** 可测量的原版物品或贴图图块；纹理尺寸显式提供，避免隐含 256 像素图集。 */
public sealed interface UiIcon permits UiIcon.Item, UiIcon.Sprite {
    int width();
    int height();
    void render(GuiGraphics graphics, int x, int y);

    /** 在内容构建时复制物品，避免调用方修改数量或组件而破坏缓存。 */
    final class Item implements UiIcon {
        private final ItemStack stack;

        public Item(ItemStack stack) { this.stack = Objects.requireNonNull(stack).copy(); }
        @Override public int width() { return 16; }
        @Override public int height() { return 16; }
        @Override public void render(GuiGraphics graphics, int x, int y) { graphics.renderItem(stack, x, y); }
    }

    /** 使用原生大小绘制的贴图区域，宽高同时作为布局尺寸。 */
    record Sprite(ResourceLocation texture, int u, int v, int width, int height,
                  int textureWidth, int textureHeight) implements UiIcon {
        public Sprite {
            Objects.requireNonNull(texture);
            if (u < 0 || v < 0 || width <= 0 || height <= 0
                    || (long) u + width > textureWidth || (long) v + height > textureHeight) {
                throw new IllegalArgumentException("Invalid sprite region");
            }
        }

        @Override public void render(GuiGraphics graphics, int x, int y) {
            graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
        }
    }
}
