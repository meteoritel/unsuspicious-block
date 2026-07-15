package com.meteorite.unsuspiciousblock.loottable.condition;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * 泥地打捞战利品条件——运行时检查玩家钓鱼竿是否拥有泥地打捞附魔，并按群系与等级计算概率。
 * <p>
 * {@code swamp=true} 时要求处于沼泽群系并享受 +15% 概率加成；
 * {@code swamp=false} 时仅按等级计算基础概率（每级 10%），不限制群系。
 * <p>
 * 同时实现 {@link LootItemCondition} 与 {@link Builder}，可直接传入
 * {@link net.minecraft.world.level.storage.loot.LootPool.Builder#when(Builder)}。
 * <p>
 * Fabric 端通过 FishingLootInjection 注入到原版钓鱼表，NeoForge 端亦可用此条件配合 GLM JSON。
 */
public record MudDredgingCondition(boolean swamp) implements LootItemCondition, LootItemCondition.Builder {

    public static final MapCodec<MudDredgingCondition> CODEC = RecordCodecBuilder.mapCodec(inst ->
            inst.group(
                    Codec.BOOL.optionalFieldOf("swamp", false).forGetter(MudDredgingCondition::swamp)
            ).apply(inst, MudDredgingCondition::new));

    // 泥地打捞概率常量
    public static final double CHANCE_PER_LEVEL = 0.10D;
    public static final double SWAMP_BONUS = 0.15D;
    public static final String SWAMP_BIOME_PATH_MARKER = "swamp";

    // 泥地打捞战利品表 Key
    public static final ResourceKey<LootTable> MUD_DREDGING =
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging"));
    public static final ResourceKey<LootTable> MUD_DREDGING_SWAMP =
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging_swamp"));

    @Override
    public boolean test(LootContext context) {
        // 钓鱼战利品上下文中 THIS_ENTITY 是 FishingHook 实体，需通过 getPlayerOwner() 获取玩家
        Entity entity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (!(entity instanceof FishingHook hook)) {
            return false;
        }
        Player player = hook.getPlayerOwner();
        if (player == null) {
            return false;
        }
        // 获取钓鱼竿
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        // 检查泥地打捞附魔等级
        int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                .getLevel(context.getLevel().holderLookup(Registries.ENCHANTMENT)
                        .getOrThrow(ModEnchantments.MUD_DREDGING));
        if (level <= 0) {
            return false;
        }
        // 获取钓鱼位置，用于群系判断
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null) {
            return false;
        }
        BlockPos pos = BlockPos.containing(origin);
        boolean isSwamp = context.getLevel().getBiome(pos).unwrapKey()
                .map(key -> key.location().getPath().contains(SWAMP_BIOME_PATH_MARKER))
                .orElse(false);

        // 沼泽条件不匹配时直接拒绝
        if (swamp && !isSwamp) {
            return false;
        }

        // 计算概率
        double chance = level * CHANCE_PER_LEVEL;
        if (isSwamp) {
            chance += SWAMP_BONUS;
        }
        boolean passed = context.getRandom().nextDouble() < Math.min(1.0D, chance);
        if (passed) {
            Constants.LOG.debug("[MudDredging] 触发泥地打捞: player={}, level={}, swamp={}, chance={}",
                    player.getName().getString(), level, isSwamp, String.format("%.0f%%", Math.min(1.0D, chance) * 100));
        }
        return passed;
    }

    @Override
    public @NotNull LootItemConditionType getType() {
        return ModLootConditions.mudDredging();
    }

    @Override
    public @NotNull LootItemCondition build() {
        return this;
    }
}