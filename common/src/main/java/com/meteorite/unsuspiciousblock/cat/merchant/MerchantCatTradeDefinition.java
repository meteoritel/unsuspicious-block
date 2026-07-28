package com.meteorite.unsuspiciousblock.cat.merchant;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * 猫猫商人单条数据驱动交易定义。
 */
public record MerchantCatTradeDefinition(
        MerchantCatTradePool pool,
        int weight,
        int maxUses,
        TradeIngredient buy,
        TradeIngredient sell,
        TradeConditions conditions) {

    /**
     * 交易物料定义——物品与物品 Tag 二选一，Tag 输出会在商人生成时随机确定具体物品。
     */
    public record TradeIngredient(ResourceLocation id, int count, boolean tag) {
    }

    /**
     * 交易生成条件——在商人生成时按候选玩家羁绊和当前维度评估。
     */
    public record TradeConditions(int minimumBond, int maximumBond, Set<ResourceLocation> dimensions) {
        public boolean matches(int catBond, ResourceLocation dimension) {
            return catBond >= this.minimumBond
                    && catBond <= this.maximumBond
                    && (this.dimensions.isEmpty() || this.dimensions.contains(dimension));
        }
    }
}
