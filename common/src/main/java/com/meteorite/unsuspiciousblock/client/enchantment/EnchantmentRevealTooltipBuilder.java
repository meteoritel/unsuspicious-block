package com.meteorite.unsuspiciousblock.client.enchantment;

import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/***
 * 附魔台 tooltip 揭示文案构建器。
 * <p>
 * 由客户端 Mixin 在渲染 tooltip 时委托：Mixin 只负责反推悬停槽位、取 {@link EnchantmentRevealClientState}
 * 缓存并回填绘制，本类承担候选列表到 tooltip 文案的转换。列表由服务端权威计算并同步
 * （见 {@code EnchantmentMenuMixin}），客户端不自行复刻随机算法。
 */
public final class EnchantmentRevealTooltipBuilder {

    // 附魔槽位的原版命中区域（相对屏幕左上角）
    private static final int SLOT_HIT_X = 60;
    private static final int SLOT_HIT_Y_BASE = 14;
    private static final int SLOT_HIT_Y_STEP = 19;
    private static final int SLOT_HIT_WIDTH = 108;
    private static final int SLOT_HIT_HEIGHT = 17;

    private EnchantmentRevealTooltipBuilder() {
    }

    // 槽位命中判定回调，由宿主屏幕提供 isHovering 实现，避免把屏幕逻辑搬进本类
    @FunctionalInterface
    public interface SlotHoverTester {
        boolean isHovering(int x, int y, int width, int height);
    }

    // 反推鼠标悬停的附魔槽位索引；返回 < 0 表示当前没有可揭示的槽位
    public static int findHoverSlot(EnchantmentMenu menu, SlotHoverTester tester) {
        for (int slot = 0; slot < 3; slot++) {
            if (menu.costs[slot] > 0 && menu.enchantClue[slot] >= 0
                    && tester.isHovering(SLOT_HIT_X, SLOT_HIT_Y_BASE + SLOT_HIT_Y_STEP * slot,
                    SLOT_HIT_WIDTH, SLOT_HIT_HEIGHT)) {
                return slot;
            }
        }
        return -1;
    }

    // 用服务端同步的候选列表重建 tooltip 行；无法重建（无缓存/无注册表/槽位无效）时返回 null，调用方回退原版文案
    public static List<Component> build(EnchantmentMenu menu, int hoverSlot,
                                        List<SyncEnchantmentRevealListPayload.Entry> synced,
                                        List<Component> original) {
        if (hoverSlot < 0 || hoverSlot >= 3 || synced == null || synced.isEmpty()) {
            return null;
        }
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        RegistryAccess registry = Minecraft.getInstance().level.registryAccess();
        Optional<Holder.Reference<Enchantment>> clueHolder = registry
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolder(menu.enchantClue[hoverSlot]);
        int clueLevel = menu.levelClue[hoverSlot];
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
            EnchantmentInstance instance = new EnchantmentInstance(holder.get(), entry.level());
            MutableComponent name = Enchantment.getFullname(instance.enchantment, instance.level).copy();
            boolean selected = clueHolder.isPresent()
                    && instance.enchantment.equals(clueHolder.get())
                    && instance.level == clueLevel;
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
        return lines;
    }
}
