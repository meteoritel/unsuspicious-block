package com.meteorite.unsuspiciousblock.client.anvil;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 铁砧成本分解客户端计算器。
 * <p>
 * 镜像 {@link AnvilMenu#createResult()} 的成本累积逻辑，从菜单当前槽位状态提取各项中间值。
 * 仅在客户端执行：客户端 AnvilMenu 同样会运行 createResult（SimpleContainer.setChanged →
 * slotsChanged → createResult），持有全部输入/输出物品与同步后的 cost DataSlot，
 * 故分解结果与原版一致（注册表无分歧时）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不修改原版逻辑，纯只读复刻</li>
 *   <li>不访问 AnvilMenu 私有字段（itemName/repairItemCountCost），renameCost 通过
 *       对比 target/result 的 CUSTOM_NAME 推断，repairCost 通过物品类型与耐久差推断</li>
 *   <li>支持本 mod 的古代金币修复分支（fabric AnvilMenuMixin）</li>
 *   <li>堆叠物品（count>1）附魔时原版将 baseCost 重置为 40，计算器标记 stackableOverride</li>
 * </ul>
 */
public final class AnvilBreakdownCalculator {

    private AnvilBreakdownCalculator() {}

    /** 原版「过于昂贵」门槛。 */
    private static final int TOO_EXPENSIVE_THRESHOLD = 40;

    /** 古代金币修复比例：每枚修复 maxDamage/4（与 fabric AnvilMenuMixin 一致）。 */
    private static final int ANCIENT_COIN_REPAIR_DIVISOR = 4;

    /**
     * 从铁砧菜单当前状态计算成本分解。
     * 调用前应确认结果槽非空（空结果时无 tooltip 可附加）。
     */
    public static AnvilBreakdown calculate(AnvilMenu menu, Player player) {
        ItemStack target = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        ItemStack material = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        int total = menu.getCost();

        int oldRepairCost = target.getOrDefault(DataComponents.REPAIR_COST, 0);
        int priorWork = oldRepairCost + material.getOrDefault(DataComponents.REPAIR_COST, 0);
        int newRepairCost = result.isEmpty()
                ? AnvilBreakdown.REPAIR_COST_UNKNOWN
                : result.getOrDefault(DataComponents.REPAIR_COST, 0);

        // 附魔变动（含 enchantCost 与 incompatibleCost）
        List<AnvilBreakdown.EnchantChange> changes = new ArrayList<>();
        boolean stackableOverride = target.getCount() > 1;
        int[] enchantAndIncompatible = computeEnchantChanges(
                target, material, player, changes, stackableOverride);
        int enchantCost = enchantAndIncompatible[0];
        int incompatibleCost = enchantAndIncompatible[1];

        // 修复费用
        int repairCost = result.isEmpty() ? 0 : computeRepairCost(target, material, result);

        // 重命名费用：通过对比 target/result 的 CUSTOM_NAME 推断
        int renameCost = result.isEmpty() ? 0 : computeRenameCost(target, result);

        boolean tooExpensive = total >= TOO_EXPENSIVE_THRESHOLD
                && !player.getAbilities().instabuild;
        boolean renameOnly = renameCost > 0 && enchantCost == 0
                && repairCost == 0 && incompatibleCost == 0;

        return new AnvilBreakdown(
                priorWork, enchantCost, repairCost, renameCost, incompatibleCost,
                total, oldRepairCost, newRepairCost,
                List.copyOf(changes), tooExpensive, renameOnly
        );
    }

    /**
     * 复刻 createResult 中附魔传递循环的逻辑，记录每条附魔的合并结果与拒绝原因。
     * 返回 [enchantCost, incompatibleCost]。
     */
    private static int[] computeEnchantChanges(
            ItemStack target, ItemStack material, Player player,
            List<AnvilBreakdown.EnchantChange> changes, boolean stackableOverride) {

        ItemEnchantments targetEnchants = EnchantmentHelper.getEnchantmentsForCrafting(target);
        ItemEnchantments matEnchants = EnchantmentHelper.getEnchantmentsForCrafting(material);
        // 与原版一致：用 Mutable 集合累积已应用附魔，后续兼容性检查基于此集合
        ItemEnchantments.Mutable applied = new ItemEnchantments.Mutable(targetEnchants);
        boolean isBook = material.has(DataComponents.STORED_ENCHANTMENTS);
        boolean instabuild = player.getAbilities().instabuild;
        boolean targetIsBook = target.is(Items.ENCHANTED_BOOK);

        int enchantCost = 0;
        int incompatibleCost = 0;
        boolean stackableResetFired = false;

        for (Object2IntMap.Entry<Holder<Enchantment>> entry : matEnchants.entrySet()) {
            Holder<Enchantment> ench = entry.getKey();
            Enchantment enchVal = ench.value();
            int targetLv = applied.getLevel(ench);
            int materialLv = entry.getIntValue();

            // 等级合并：相同 → +1；不同 → 取较大值
            int mergedLv = (targetLv == materialLv) ? materialLv + 1 : Math.max(materialLv, targetLv);

            // 可施加性判定
            boolean supported = enchVal.canEnchant(target);
            if (instabuild || targetIsBook) {
                supported = true;
            }

            // 兼容性判定：与 applied 集合中已有附魔逐对检查
            boolean compatible = true;
            for (Holder<Enchantment> existing : applied.keySet()) {
                if (!existing.equals(ench) && !Enchantment.areCompatible(ench, existing)) {
                    compatible = false;
                    incompatibleCost++;  // 与原版 baseCost++ 一致：每对互斥 +1
                }
            }

            boolean canApply = supported && compatible;
            String rejectReason = null;
            if (!canApply) {
                // 优先标记「不适用」：supported 失败时即使后续 compatible 也不生效
                rejectReason = !supported
                        ? AnvilBreakdown.EnchantChange.REASON_NOT_SUPPORTED
                        : AnvilBreakdown.EnchantChange.REASON_INCOMPATIBLE;
            }

            int costContribution = 0;
            int resultLevel = mergedLv;
            if (canApply) {
                // 截断到 maxLevel
                if (mergedLv > enchVal.getMaxLevel()) {
                    mergedLv = enchVal.getMaxLevel();
                }
                resultLevel = mergedLv;
                applied.set(ench, mergedLv);

                int anvilCost = enchVal.getAnvilCost();
                if (isBook) {
                    anvilCost = Math.max(1, anvilCost / 2);
                }
                costContribution = anvilCost * mergedLv;

                // 堆叠物品特殊处理：原版在循环内直接 baseCost = 40
                if (stackableOverride && !stackableResetFired) {
                    // 首次应用附魔时 baseCost 被重置为 40，此前的 enchantCost 累积被覆盖
                    enchantCost = TOO_EXPENSIVE_THRESHOLD;
                    stackableResetFired = true;
                } else {
                    enchantCost += costContribution;
                }
            }

            changes.add(new AnvilBreakdown.EnchantChange(
                    ench, targetLv, materialLv, resultLevel,
                    costContribution, canApply, rejectReason
            ));
        }

        return new int[]{enchantCost, incompatibleCost};
    }

    /**
     * 推断修复费用。
     * 通过对比 target/result 的耐久差与材料类型，复刻原版两大修复分支。
     */
    private static int computeRepairCost(ItemStack target, ItemStack material, ItemStack result) {
        if (!target.isDamageableItem() || material.isEmpty()) {
            return 0;
        }
        int damageRestored = target.getDamageValue() - result.getDamageValue();
        if (damageRestored <= 0) {
            return 0;
        }
        int repairPerItem = Math.max(1, target.getMaxDamage() / ANCIENT_COIN_REPAIR_DIVISOR);

        // 分支 A：古代金币修复（本 mod fabric 分支）
        if (isAncientCoin(material)) {
            return Math.min(material.getCount(), ceilDiv(damageRestored, repairPerItem));
        }
        // 分支 B：原版材料修复
        if (target.getItem().isValidRepairItem(target, material)) {
            return Math.min(material.getCount(), ceilDiv(damageRestored, repairPerItem));
        }
        // 分支 C：原版同种物品合并耐久（固定 +2）
        boolean isStoredEnchBook = material.has(DataComponents.STORED_ENCHANTMENTS);
        if (!isStoredEnchBook && target.is(material.getItem()) && target.isDamageableItem()) {
            return 2;
        }
        return 0;
    }

    /** 判断材料是否为古代金币（本 mod 物品，避免在 common 引用平台类）。 */
    private static boolean isAncientCoin(ItemStack stack) {
        return com.meteorite.unsuspiciousblock.item.ModItems.ANCIENT_COIN != null
                && stack.is(com.meteorite.unsuspiciousblock.item.ModItems.ANCIENT_COIN);
    }

    /** 向上取整除法，用于计算修复消耗份数。 */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * 推断重命名费用。
     * 通过对比 target 与 result 的 CUSTOM_NAME 组件状态，复刻原版 setItemName 的影响。
     */
    private static int computeRenameCost(ItemStack target, ItemStack result) {
        boolean targetHasCustom = target.has(DataComponents.CUSTOM_NAME);
        boolean resultHasCustom = result.has(DataComponents.CUSTOM_NAME);
        if (resultHasCustom && !targetHasCustom) {
            return 1;  // 新增自定义名
        }
        if (resultHasCustom) {
            Component targetName = target.get(DataComponents.CUSTOM_NAME);
            Component resultName = result.get(DataComponents.CUSTOM_NAME);
            return (targetName != null && !targetName.equals(resultName)) ? 1 : 0;
        }
        if (targetHasCustom) {
            return 1;  // 清除自定义名
        }
        return 0;
    }
}
