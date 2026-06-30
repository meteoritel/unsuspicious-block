package com.meteorite.unsuspiciousblock.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 附魔台屏幕 tooltip 揭示 hook。
 * 通过包裹 {@link GuiGraphics#renderComponentTooltip} 调用，
 * 在客户端缓存中存在服务端下发的完整候选列表时，
 * 用完整列表替换原版的单条随机条目 tooltip。
 * 其余情况原样放行，保持 vanilla 行为与其他模组的 tooltip 修改兼容。
 * <p>
 * 列表由服务端权威计算并同步（见 {@code EnchantmentMenuMixin}），
 * 客户端仅查询 {@link EnchantmentRevealClientState} 缓存，不自行复刻算法，
 * 以避免客户端 registryAccess 与服务端不一致导致的差异。
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
        // 1. 反推 hover 的 slot 索引
        int hoverSlot = -1;
        for (int s = 0; s < 3; s++) {
            if (menu.costs[s] > 0 && menu.enchantClue[s] >= 0
                    && this.isHovering(60, 14 + 19 * s, 108, 17, mouseX, mouseY)) {
                hoverSlot = s;
                break;
            }
        }
        if (hoverSlot < 0) {
            originalOp.call(guiGraphics, font, original, mouseX, mouseY);
            return;
        }
        // 2. 查询服务端同步的候选列表；无缓存则回退 vanilla tooltip
        List<SyncEnchantmentRevealListPayload.Entry> synced =
                EnchantmentRevealClientState.getList(menu.containerId, hoverSlot);
        if (synced == null || synced.isEmpty()) {
            originalOp.call(guiGraphics, font, original, mouseX, mouseY);
            return;
        }
        // 3. 通过客户端注册表解析附魔 holder（用于显示名称与选中标记）
        if (Minecraft.getInstance().level == null) {
            originalOp.call(guiGraphics, font, original, mouseX, mouseY);
            return;
        }
        RegistryAccess registry = Minecraft.getInstance().level.registryAccess();
        Optional<Holder.Reference<Enchantment>> clueHolder = registry
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolder(menu.enchantClue[hoverSlot]);
        int clueLevel = menu.levelClue[hoverSlot];
        // 4. 重建 tooltip：header + 候选条目 + 原版剩余行（EMPTY + lapis/level 信息）
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("unsuspiciousblock.container.enchant.reveal.header")
                .withStyle(ChatFormatting.WHITE));
        // 原版同步到客户端的候选（enchantClue/levelClue 对应条目）排在候选列表第一行
        MutableComponent selectedName = null;
        List<MutableComponent> otherNames = new ArrayList<>();
        for (SyncEnchantmentRevealListPayload.Entry entry : synced) {
            Optional<Holder.Reference<Enchantment>> holder = registry
                    .registryOrThrow(Registries.ENCHANTMENT)
                    .getHolder(entry.enchantId());
            if (holder.isEmpty()) {
                continue;
            }
            EnchantmentInstance inst = new EnchantmentInstance(holder.get(), entry.level());
            MutableComponent name = Enchantment.getFullname(inst.enchantment, inst.level).copy();
            boolean selected = clueHolder.isPresent()
                    && inst.enchantment.equals(clueHolder.get())
                    && inst.level == clueLevel;
            name.withStyle(selected ? ChatFormatting.GOLD : ChatFormatting.GRAY);
            if (selected) {
                selectedName = name;
            } else {
                otherNames.add(name);
            }
        }
        if (selectedName != null) {
            lines.add(selectedName);
        }
        lines.addAll(otherNames);
        // 跳过原版 line 0（clue 行），保留 EMPTY 分隔行与 lapis/level 要求行
        if (original.size() > 1) {
            lines.addAll(original.subList(1, original.size()));
        }
        originalOp.call(guiGraphics, font, lines, mouseX, mouseY);
    }
}
