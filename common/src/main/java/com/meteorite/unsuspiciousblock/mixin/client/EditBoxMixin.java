package com.meteorite.unsuspiciousblock.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.client.ui.widget.ShadowlessEditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 输入框文字渲染入口，仅为 {@link ShadowlessEditBox} 关闭原版文字阴影。
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin {

    // 关闭输入内容及其格式化片段的文字阴影
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),
            require = 0)
    private int unsuspiciousblock$drawFormattedTextWithoutShadow(
            GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color,
            Operation<Integer> original) {
        if ((Object) this instanceof ShadowlessEditBox) {
            return graphics.drawString(font, text, x, y, color, false);
        }
        return original.call(graphics, font, text, x, y, color);
    }

    // 关闭搜索提示文字的阴影
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0)
    private int unsuspiciousblock$drawComponentWithoutShadow(
            GuiGraphics graphics, Font font, Component text, int x, int y, int color,
            Operation<Integer> original) {
        if ((Object) this instanceof ShadowlessEditBox) {
            return graphics.drawString(font, text, x, y, color, false);
        }
        return original.call(graphics, font, text, x, y, color);
    }

    // 关闭补全建议及下划线光标的文字阴影
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"),
            require = 0)
    private int unsuspiciousblock$drawStringWithoutShadow(
            GuiGraphics graphics, Font font, String text, int x, int y, int color,
            Operation<Integer> original) {
        if ((Object) this instanceof ShadowlessEditBox) {
            return graphics.drawString(font, text, x, y, color, false);
        }
        return original.call(graphics, font, text, x, y, color);
    }

    // 兼容 NeoForge 为输入内容增加的文字阴影参数
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"),
            require = 0)
    private int unsuspiciousblock$drawFormattedTextWithoutShadowNeoForge(
            GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color, boolean dropShadow,
            Operation<Integer> original) {
        return original.call(graphics, font, text, x, y, color,
                !((EditBox) (Object) this instanceof ShadowlessEditBox) && dropShadow);
    }

    // 兼容 NeoForge 为搜索提示文字增加的文字阴影参数
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0)
    private int unsuspiciousblock$drawComponentWithoutShadowNeoForge(
            GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean dropShadow,
            Operation<Integer> original) {
        return original.call(graphics, font, text, x, y, color,
                !((EditBox) (Object) this instanceof ShadowlessEditBox) && dropShadow);
    }

    // 兼容 NeoForge 为补全建议及下划线光标增加的文字阴影参数
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"),
            require = 0)
    private int unsuspiciousblock$drawStringWithoutShadowNeoForge(
            GuiGraphics graphics, Font font, String text, int x, int y, int color, boolean dropShadow,
            Operation<Integer> original) {
        return original.call(graphics, font, text, x, y, color,
                !((EditBox) (Object) this instanceof ShadowlessEditBox) && dropShadow);
    }
}
