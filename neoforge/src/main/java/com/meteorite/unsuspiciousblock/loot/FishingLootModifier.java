package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/**
 * 钓鱼战利品注入 GLM——向原版钓鱼表追加泥地打捞额外战利品。
 * <p>
 * 在 {@link #doApply(ObjectArrayList, LootContext)} 中运行时检查：
 * 玩家钓鱼竿是否有泥地打捞附魔 → 判断群系 → 按等级+群系加成计算概率 →
 * 命中后加载对应 mud_dredging 战利品表并追加物品。
 * <p>
 * 与 {@link AddItemLootModifier} / {@link InjectItemLootModifier} 的"替换/追加固定物品"
 * 语义不同，本修改器保留原版所有掉落并追加一整张战利品表的产出。
 */
public class FishingLootModifier extends LootModifier {

    public static final MapCodec<FishingLootModifier> CODEC = RecordCodecBuilder.mapCodec(inst ->
            inst.group(
                    IGlobalLootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(m -> m.conditions)
            ).apply(inst, FishingLootModifier::new));

    private static final ResourceKey<LootTable> MUD_DREDGING =
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging"));

    private static final ResourceKey<LootTable> MUD_DREDGING_SWAMP =
            ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging_swamp"));

    private static final double CHANCE_PER_LEVEL = 0.10D;
    private static final double SWAMP_BONUS = 0.15D;
    private static final String SWAMP_BIOME_PATH_MARKER = "swamp";

    public FishingLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(@NotNull ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        // 获取玩家实体
        if (!(context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof Player player)) {
            return generatedLoot;
        }
        // 获取钓鱼竿
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null || tool.isEmpty()) {
            return generatedLoot;
        }
        // 检查泥地打捞附魔等级
        int level = tool.getEnchantments().getLevel(
                context.getLevel().holderLookup(Registries.ENCHANTMENT)
                        .getOrThrow(ModEnchantments.MUD_DREDGING));
        if (level <= 0) {
            return generatedLoot;
        }
        // 获取钓鱼位置，用于群系判断
        var origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null) {
            return generatedLoot;
        }
        BlockPos pos = BlockPos.containing(origin);
        boolean isSwamp = context.getLevel().getBiome(pos).unwrapKey()
                .map(key -> key.location().getPath().contains(SWAMP_BIOME_PATH_MARKER))
                .orElse(false);

        // 计算概率
        double chance = level * CHANCE_PER_LEVEL;
        if (isSwamp) {
            chance += SWAMP_BONUS;
        }
        if (context.getRandom().nextDouble() >= Math.min(1.0D, chance)) {
            return generatedLoot;
        }

        // 命中：按群系选择对应的 mud_dredging 战利品表并追加物品
        ResourceKey<LootTable> tableKey = isSwamp ? MUD_DREDGING_SWAMP : MUD_DREDGING;
        LootTable lootTable = context.getLevel().getServer()
                .reloadableRegistries().getLootTable(tableKey);
        lootTable.getRandomItems(context, generatedLoot::add);
        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}