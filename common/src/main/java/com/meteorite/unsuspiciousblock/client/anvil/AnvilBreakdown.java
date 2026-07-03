package com.meteorite.unsuspiciousblock.client.anvil;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 铁砧成本分解数据。
 * 由 {@link AnvilBreakdownCalculator} 在客户端从 {@link net.minecraft.world.inventory.AnvilMenu}
 * 当前槽位状态镜像原版 {@code createResult} 逻辑计算得出，供 {@link AnvilBreakdownTooltipAppender} 追加到 tooltip。
 *
 * <p>所有 cost 字段单位均为「经验等级」，与 {@link net.minecraft.world.inventory.AnvilMenu#getCost()} 一致。
 * 各分量之和未必严格等于 {@link #total}：当其他 mod 改写铁砧算法时以 {@link #total} 为权威，
 * 差异由渲染层归入「其他」行。</p>
 */
public record AnvilBreakdown(
        int priorWork,
        int enchantCost,
        int repairCost,
        int renameCost,
        int incompatibleCost,
        int total,
        int oldRepairCost,
        int newRepairCost,
        List<EnchantChange> changes,
        boolean tooExpensive,
        boolean renameOnly
) {

    /** 结果槽为空时 newRepairCost 不可读，用此哨兵值标记「未知」。 */
    public static final int REPAIR_COST_UNKNOWN = -1;

    /** 单条附魔变动：附魔 holder + 目标/材料/结果等级 + 费用贡献 + 是否生效 + 拒绝原因。 */
    public record EnchantChange(
            Holder<Enchantment> enchant,
            int targetLevel,
            int materialLevel,
            int resultLevel,
            int costContribution,
            boolean applied,
            @Nullable String rejectReason
    ) {

        /** 拒绝原因：与目标已有附魔互斥。 */
        public static final String REASON_INCOMPATIBLE = "incompatible";

        /** 拒绝原因：该附魔不适用于目标物品（supported_items 不含）。 */
        public static final String REASON_NOT_SUPPORTED = "not_supported";
    }
}
