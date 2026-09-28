package com.meteorite.unsuspiciousblock.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.widget.ShadowlessEditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 输入框文字渲染入口，仅为 {@link ShadowlessEditBox} 关闭原版文字阴影。
 *
 * <p>6 个 {@code @WrapOperation} 覆盖原版与 NeoForge 两套 {@code GuiGraphics#drawString} 重载：
 * 两端的 {@code EditBox.renderWidget} 调用的是不同的重载集合（带/不带 {@code dropShadow} 参数），
 * 而本类同时服务两端，因此无法对任一端把 {@code require} 提为 1——那会让另一端整包加载失败。
 * 作为替代，这里做两件事：
 * <ol>
 *   <li>包装器只做"判定 + 转发"，判定收敛到 {@link #markShadowlessHit(Object)} / {@link #keepDropShadow(Object, boolean)} 两个共享方法；</li>
 *   <li>在 {@code renderWidget} 头部做一次廉价的运行期自检：非空输入的 {@link ShadowlessEditBox}
 *       连续多帧渲染却没有任何包装器命中时告警（详见 {@link #usb$selfCheckWrapperCoverage}），
 *       避免签名不匹配时静默失效。</li>
 * </ol>
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin {

    // 自检状态：是否有任何包装器命中过；非空输入却连续未命中的帧数
    private static volatile boolean shadowlessWrapperHit;
    private static int missedTextRenders;
    private static boolean selfCheckWarned;

    // 包装器命中即证明本平台的注入目标存在；命中且为无阴影输入框时返回 true
    private static boolean markShadowlessHit(Object self) {
        if (self instanceof ShadowlessEditBox) {
            shadowlessWrapperHit = true;
            return true;
        }
        return false;
    }

    // 带 dropShadow 参数的重载：无阴影输入框一律关闭阴影，其余保持原参数
    private static boolean keepDropShadow(Object self, boolean dropShadow) {
        return !(self instanceof ShadowlessEditBox) && dropShadow;
    }

    /**
     * 启动期自检：{@link ShadowlessEditBox} 有非空文本却没触发任何包装器，说明当前平台的
     * {@code EditBox.renderWidget} 没有调用本类声明的 {@code drawString} 重载（{@code require = 0} 会静默失效）。
     * 连续 {@code SELF_CHECK_RENDERS} 帧才告警一次，避免空输入框或极窄裁剪造成误报。
     */
    @Inject(method = "renderWidget", at = @At("HEAD"))
    private void usb$selfCheckWrapperCoverage(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                              CallbackInfo ci) {
        if (!((Object) this instanceof ShadowlessEditBox) || shadowlessWrapperHit) {
            return;
        }
        // 空输入框本来就不会走到文字绘制，不参与统计
        if (((EditBox) (Object) this).getValue().isEmpty()) {
            return;
        }
        if (++missedTextRenders >= 3 && !selfCheckWarned) {
            selfCheckWarned = true;
            Constants.LOG.warn("ShadowlessEditBox 的文字包装器从未命中：当前平台的 EditBox.renderWidget "
                    + "可能未调用已声明的 GuiGraphics.drawString 重载，输入框文字将带阴影");
        }
    }

    // 关闭输入内容及其格式化片段的文字阴影
    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),
            require = 0)
    private int unsuspiciousblock$drawFormattedTextWithoutShadow(
            GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color,
            Operation<Integer> original) {
        return markShadowlessHit(this)
                ? graphics.drawString(font, text, x, y, color, false)
                : original.call(graphics, font, text, x, y, color);
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
        return markShadowlessHit(this)
                ? graphics.drawString(font, text, x, y, color, false)
                : original.call(graphics, font, text, x, y, color);
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
        return markShadowlessHit(this)
                ? graphics.drawString(font, text, x, y, color, false)
                : original.call(graphics, font, text, x, y, color);
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
        markShadowlessHit(this);
        return original.call(graphics, font, text, x, y, color, keepDropShadow(this, dropShadow));
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
        markShadowlessHit(this);
        return original.call(graphics, font, text, x, y, color, keepDropShadow(this, dropShadow));
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
        markShadowlessHit(this);
        return original.call(graphics, font, text, x, y, color, keepDropShadow(this, dropShadow));
    }
}
