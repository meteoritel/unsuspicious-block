package com.meteorite.unsuspiciousblock.client.ui.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 无文字阴影的输入框标记类型，具体渲染行为由客户端 EditBox mixin 提供。
 */
public final class ShadowlessEditBox extends EditBox {

    public ShadowlessEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }
}
