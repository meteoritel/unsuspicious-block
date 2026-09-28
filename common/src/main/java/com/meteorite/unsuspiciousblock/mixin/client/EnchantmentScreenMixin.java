package com.meteorite.unsuspiciousblock.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealTooltipBuilder;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * 附魔台屏幕 tooltip 揭示 hook。
 * 仅作为入口：反推悬停槽位、取客户端缓存，然后委托 {@link EnchantmentRevealTooltipBuilder}
 * 重建 tooltip 行；无法重建时原样放行，保持 vanilla 行为与其他模组的 tooltip 修改兼容。
 * <p>
 * 列表由服务端权威计算并同步（见 {@code EnchantmentMenuMixin}），
 * 客户端仅查询 {@link EnchantmentRevealClientState} 缓存，不自行复刻算法。
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin extends AbstractContainerScreen<EnchantmentMenu> {

    // 仅为编译期满足父类构造要求；运行时由目标类自己的构造函数生效
    private EnchantmentScreenMixin(EnchantmentMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    // 包裹 renderComponentTooltip 调用——这是 EnchantmentScreen.render 中构建附魔 tooltip 的唯一出口
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"))
    private void unsuspiciousblock$revealFullList(
            GuiGraphics guiGraphics, Font font, List<Component> original, int mouseX, int mouseY,
            Operation<Void> originalOp) {
        EnchantmentMenu menu = this.menu;
        int hoverSlot = EnchantmentRevealTooltipBuilder.findHoverSlot(menu,
                (x, y, width, height) -> this.isHovering(x, y, width, height, mouseX, mouseY));
        List<SyncEnchantmentRevealListPayload.Entry> synced = hoverSlot < 0
                ? null
                : EnchantmentRevealClientState.getList(menu.containerId, hoverSlot);
        List<Component> reveal = EnchantmentRevealTooltipBuilder.build(menu, hoverSlot, synced, original);
        originalOp.call(guiGraphics, font, reveal != null ? reveal : original, mouseX, mouseY);
    }
}
