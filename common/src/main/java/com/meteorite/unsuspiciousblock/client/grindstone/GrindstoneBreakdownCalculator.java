package com.meteorite.unsuspiciousblock.client.grindstone;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 砂轮操作分解客户端计算器。
 * <p>
 * 与铁砧计算器不同，砂轮的 {@code computeResult} 在客户端也完整执行，
 * 结果槽已包含最终产物，因此本类仅做<b>差异分析</b>：对比输入槽与结果槽，
 * 推导出将被移除的非诅咒附魔、将保留的诅咒附魔、经验返还范围、耐久变化等。
 *
 * <p>经验计算复刻原版 {@code GrindstoneMenu.getExperienceAmount}：
 * <pre>total = Σ ench.getMinCost(level) （两输入槽的所有非诅咒附魔）
 * 返还范围 = [ceil(total/2), total]</pre>
 *
 * <p>所有数据均从菜单槽位只读提取，不修改原版逻辑。
 */
public final class GrindstoneBreakdownCalculator {

    private GrindstoneBreakdownCalculator() {}

    /**
     * 从砂轮菜单当前状态分析操作分解。
     *
     * @return 结果槽为空时返回 {@code null}（无 tooltip 可附加）
     */
    public static GrindstoneBreakdown calculate(GrindstoneMenu menu) {
        ItemStack input1 = menu.getSlot(GrindstoneMenu.INPUT_SLOT).getItem();
        ItemStack input2 = menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).getItem();
        ItemStack result = menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem();

        if (result.isEmpty()) {
            return null;
        }

        // 操作类型：input2 为空 → 单槽去魔；否则 → 双槽合并
        GrindstoneBreakdown.OperationType opType = input2.isEmpty()
                ? GrindstoneBreakdown.OperationType.DISENCHANT
                : GrindstoneBreakdown.OperationType.MERGE_REPAIR;

        // 移除的非诅咒附魔：遍历两个输入槽
        List<GrindstoneBreakdown.EnchantEntry> removed = new ArrayList<>();
        int expTotal = 0;
        expTotal += collectNonCursesAndExp(input1, removed);
        if (!input2.isEmpty()) {
            expTotal += collectNonCursesAndExp(input2, removed);
        }

        // 保留的诅咒附魔：直接从结果槽读取（结果仅含诅咒）
        List<GrindstoneBreakdown.EnchantEntry> kept = new ArrayList<>();
        collectCurses(result, kept);

        // 经验返还范围
        int expMin = expTotal > 0 ? (int) Math.ceil(expTotal / 2.0) : 0;
        int expMax = expTotal;

        // REPAIR_COST 变化
        int oldRepairCost = input1.getOrDefault(DataComponents.REPAIR_COST, 0);
        int newRepairCost = result.getOrDefault(DataComponents.REPAIR_COST, 0);

        // 耐久变化（仅合并修复）
        boolean hasDurabilityChange = false;
        int oldDurability = 0;
        int newDurability = 0;
        if (opType == GrindstoneBreakdown.OperationType.MERGE_REPAIR && result.isDamageableItem()) {
            oldDurability = input1.getMaxDamage() - input1.getDamageValue();
            newDurability = result.getMaxDamage() - result.getDamageValue();
            hasDurabilityChange = true;
        }

        // 附魔书 → 普通书
        boolean bookTransformed = input1.is(Items.ENCHANTED_BOOK) && result.is(Items.BOOK);

        return new GrindstoneBreakdown(
                opType,
                List.copyOf(removed),
                List.copyOf(kept),
                expMin, expMax,
                oldRepairCost, newRepairCost,
                hasDurabilityChange, oldDurability, newDurability,
                bookTransformed
        );
    }

    /**
     * 收集物品上的非诅咒附魔到列表，并累加经验值。
     *
     * @return 该物品非诅咒附魔的经验总和
     */
    private static int collectNonCursesAndExp(ItemStack stack, List<GrindstoneBreakdown.EnchantEntry> out) {
        if (stack.isEmpty()) {
            return 0;
        }
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        int exp = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchants.entrySet()) {
            Holder<Enchantment> ench = entry.getKey();
            int level = entry.getIntValue();
            if (!ench.is(EnchantmentTags.CURSE)) {
                out.add(new GrindstoneBreakdown.EnchantEntry(ench, level));
                exp += ench.value().getMinCost(level);
            }
        }
        return exp;
    }

    /** 收集物品上的诅咒附魔到列表（用于结果槽的「将保留」展示）。 */
    private static void collectCurses(ItemStack stack, List<GrindstoneBreakdown.EnchantEntry> out) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchants.entrySet()) {
            Holder<Enchantment> ench = entry.getKey();
            if (ench.is(EnchantmentTags.CURSE)) {
                out.add(new GrindstoneBreakdown.EnchantEntry(ench, entry.getIntValue()));
            }
        }
    }
}
