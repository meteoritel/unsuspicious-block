package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import com.meteorite.unsuspiciousblock.loottable.condition.MudDredgingCondition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

    public FishingLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(@NotNull ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        // 钓鱼战利品上下文中 THIS_ENTITY 是 FishingHook 实体，需通过 getPlayerOwner() 获取玩家
        Entity entity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (!(entity instanceof FishingHook hook)) {
            return generatedLoot;
        }
        Player player = hook.getPlayerOwner();
        if (player == null) {
            return generatedLoot;
        }
        // 获取钓鱼竿
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null || tool.isEmpty()) {
            return generatedLoot;
        }
        // 检查泥地打捞附魔等级
        int level = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                .getLevel(context.getLevel().holderLookup(Registries.ENCHANTMENT)
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
                .map(key -> key.location().getPath().contains(MudDredgingCondition.SWAMP_BIOME_PATH_MARKER))
                .orElse(false);

        // 计算概率
        double chance = level * MudDredgingCondition.CHANCE_PER_LEVEL;
        if (isSwamp) {
            chance += MudDredgingCondition.SWAMP_BONUS;
        }
        if (context.getRandom().nextDouble() >= Math.min(1.0D, chance)) {
            return generatedLoot;
        }

        // 命中：按群系选择对应的 mud_dredging 战利品表并追加物品
        ResourceKey<LootTable> tableKey = isSwamp ? MudDredgingCondition.MUD_DREDGING_SWAMP : MudDredgingCondition.MUD_DREDGING;
        LootTable lootTable = context.getLevel().getServer()
                .reloadableRegistries().getLootTable(tableKey);

        // 推送追踪上下文，使 NestedLootTableMixin 能捕获嵌套子表物品
        ServerPlayer sp = (ServerPlayer) player;
        LootTrackingContext trackingCtx = LootTrackingContext.root(
                sp, tableKey.location(), LootSourceType.FISHING,
                context.getLevel().getGameTime(), context.getLevel().getDayTime(),
                pos, null);
        LootSession lootSession = new LootSession(trackingCtx);

        // 包装 Consumer 以捕获 mud_dredging 表直接产出的物品
        List<ItemStack> captured = new ArrayList<>();
        Consumer<ItemStack> wrappedConsumer = stack -> {
            if (!stack.isEmpty()) {
                captured.add(stack.copy());
            }
            generatedLoot.add(stack);
        };

        int before = generatedLoot.size();
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(lootSession, trackingCtx)) {
            // 使用 LootParams 新建上下文，避免复用原 context 导致 GLM 无限递归
            LootParams mudParams = new LootParams.Builder(context.getLevel())
                    .withParameter(LootContextParams.ORIGIN, origin)
                    .withParameter(LootContextParams.TOOL, tool)
                    .withParameter(LootContextParams.THIS_ENTITY, entity)
                    .withLuck(context.getLuck())
                    .create(LootContextParamSets.FISHING);
            lootTable.getRandomItems(mudParams, wrappedConsumer);
        }

        // 提交最终物品；嵌套子表物品已由 NestedLootTableMixin 汇入同一会话
        Map<String, Integer> itemCounts = new HashMap<>();
        for (ItemStack stack : captured) {
            LootResultSignature signature = ArchaeologyLootRuntimeTracker.resolveSignature(
                    trackingCtx.rootTableId(), stack);
            if (signature != null) {
                itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
            }
        }
        if (!itemCounts.isEmpty()) {
            LootTrackingEvents.submit(lootSession, itemCounts, LootSettlementStrategies.immediate());
        }

        Constants.LOG.debug("[MudDredging] GLM 追加战利品: player={}, level={}, swamp={}, table={}, added={}",
                player.getName().getString(), level, isSwamp, tableKey.location(),
                generatedLoot.size() - before);
        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
