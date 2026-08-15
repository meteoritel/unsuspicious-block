package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.slf4j.Logger;

/**
 * 战利品上下文参数填充器——按战利品表声明的 LootContextParamSet 的 required 参数，
 * 向 LootParams.Builder 填充合理的默认值，使模拟抽取能在不同表类型下正常执行。
 * 策略：
 * - 简单标量/状态参数（TOOL/BLOCK_STATE/DAMAGE_SOURCE/EXPLOSION_RADIUS/BLOCK_ENTITY）用虚拟默认值填充
 * - 实体参数（THIS_ENTITY/ATTACKING_ENTITY/DIRECT_ATTACKING_ENTITY/LAST_DAMAGE_PLAYER）用 SimulationFakePlayer 填充；
 *   钓鱼类表的 THIS_ENTITY 例外，改用 SimulationFishingHook（owner 为假玩家）
 * - SimulationFakePlayer 为原版 ServerPlayer 子类，无在线玩家时也可构造，NeoForge/Fabric 通用
 */
public final class LootContextParamFiller {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LootContextParamFiller() {
    }

    /**
     * 为模拟构建 LootParams。
     * 按 paramSet 的 required 参数填充；实体参数用 SimulationFakePlayer 填充（无需在线玩家），
     * 钓鱼类表的 THIS_ENTITY 改用 SimulationFishingHook（使 fishing_hook 谓词条件可判定）。
     * 若存在完全未知的 required 参数，回退到仅 ORIGIN 的宽松 paramSet。
     */
    public static LootParams createForSimulation(ServerLevel level, LootContextParamSet paramSet,
                                                 SimulationProfile profile) {
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, profile.origin())
                .withLuck(profile.luck());

        if (fillRequired(builder, paramSet, level, profile)) {
            return builder.create(paramSet);
        }

        // 回退：原 paramSet 有完全未支持的 required 参数（非已知实体/标量参数），
        // 构建仅 ORIGIN 的宽松 paramSet。依赖未填充参数的条件会按原版逻辑失败，
        // 由上层将零出现的条件条目保留为未知概率，避免报告错误的确定值。
        LOGGER.info("回退到宽松 paramSet 模拟（原 paramSet 存在未支持的 required 参数）");
        LootContextParamSet looseParamSet = LootContextParamSet.builder()
                .required(LootContextParams.ORIGIN)
                .build();
        LootParams.Builder looseBuilder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, profile.origin())
                .withLuck(profile.luck());
        return looseBuilder.create(looseParamSet);
    }

    /**
     * 按 paramSet 的 required 参数填充 builder。
     * ORIGIN 由调用方已设置，此处跳过；其余参数按常见表类型提供默认值。
     *
     * @return true 若全部 required 参数均已支持填充；false 若存在未支持的 required 参数
     */
    private static boolean fillRequired(LootParams.Builder builder, LootContextParamSet paramSet,
                                        ServerLevel level, SimulationProfile profile) {
        // 延迟构造虚拟玩家：仅当遇到实体参数时才创建，避免无实体参数表的无效开销
        SimulationFakePlayer fakePlayer = null;
        boolean allSupported = true;
        boolean fishingHookFilled = false;

        for (LootContextParam<?> param : paramSet.getRequired()) {
            if (param == LootContextParams.ORIGIN) {
                continue;
            }

            if (isEntityParam(param)) {
                if (fakePlayer == null) {
                    fakePlayer = new SimulationFakePlayer(level.getServer(), level);
                }
                // 钓鱼类表的 THIS_ENTITY 用假浮标填充（owner 为假玩家），
                // 使 entity_properties + fishing_hook + in_open_water 等条件在模拟中可判定
                if (param == LootContextParams.THIS_ENTITY && profile.fishingContext()) {
                    builder.withParameter(LootContextParams.THIS_ENTITY,
                            new SimulationFishingHook(fakePlayer, level));
                    fishingHookFilled = true;
                    continue;
                }
                fillEntityParam(builder, param, fakePlayer);
                continue;
            }

            if (!fillScalarParam(builder, param, profile)) {
                allSupported = false;
            }
        }

        // 钓鱼 paramSet（minecraft:fishing）中 THIS_ENTITY 是 optional 参数，仅遍历 required 不会填充；
        // 而开放水域（fishing_hook）等条件依赖 THIS_ENTITY 存在，此处对钓鱼类表按 allowed 集合补填假浮标。
        // paramSet 不允许 THIS_ENTITY 时跳过（create 会拒绝 allowed 之外的参数）
        if (!fishingHookFilled && profile.fishingContext()
                && paramSet.isAllowed(LootContextParams.THIS_ENTITY)) {
            if (fakePlayer == null) {
                fakePlayer = new SimulationFakePlayer(level.getServer(), level);
            }
            builder.withParameter(LootContextParams.THIS_ENTITY,
                    new SimulationFishingHook(fakePlayer, level));
        }
        return allSupported;
    }

    // 判断是否为实体类参数（用虚拟玩家填充）
    private static boolean isEntityParam(LootContextParam<?> param) {
        return param == LootContextParams.THIS_ENTITY
                || param == LootContextParams.ATTACKING_ENTITY
                || param == LootContextParams.DIRECT_ATTACKING_ENTITY
                || param == LootContextParams.LAST_DAMAGE_PLAYER;
    }

    // 按具体参数常量填充实体参数；LAST_DAMAGE_PLAYER 为 LootContextParam<Player>，ServerPlayer 兼容
    private static void fillEntityParam(LootParams.Builder builder, LootContextParam<?> param, SimulationFakePlayer player) {
        if (param == LootContextParams.THIS_ENTITY) {
            builder.withParameter(LootContextParams.THIS_ENTITY, player);
        } else if (param == LootContextParams.ATTACKING_ENTITY) {
            builder.withParameter(LootContextParams.ATTACKING_ENTITY, player);
        } else if (param == LootContextParams.DIRECT_ATTACKING_ENTITY) {
            builder.withParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, player);
        } else if (param == LootContextParams.LAST_DAMAGE_PLAYER) {
            builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, player);
        }
    }

    // 填充非实体参数的默认值；返回 false 表示该参数未支持
    private static boolean fillScalarParam(LootParams.Builder builder, LootContextParam<?> param,
                                           SimulationProfile profile) {
        if (param == LootContextParams.TOOL) {
            builder.withParameter(LootContextParams.TOOL, profile.tool().copy());
            return true;
        }
        if (param == LootContextParams.BLOCK_STATE) {
            builder.withParameter(LootContextParams.BLOCK_STATE, profile.blockState());
            return true;
        }
        if (param == LootContextParams.DAMAGE_SOURCE) {
            builder.withParameter(LootContextParams.DAMAGE_SOURCE, profile.damageSource());
            return true;
        }
        if (param == LootContextParams.EXPLOSION_RADIUS) {
            builder.withParameter(LootContextParams.EXPLOSION_RADIUS, profile.explosionRadius());
            return true;
        }
        if (param == LootContextParams.BLOCK_ENTITY) {
            // 用 dummy 箱子方块实体填充；绝大多数 generic 表不实际访问它
            BlockEntity dummy = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
            builder.withParameter(LootContextParams.BLOCK_ENTITY, dummy);
            return true;
        }
        // 未知参数，返回 false 触发回退
        LOGGER.warn("未支持的 required LootContextParam: {}, 表模拟可能回退", param);
        return false;
    }
}
