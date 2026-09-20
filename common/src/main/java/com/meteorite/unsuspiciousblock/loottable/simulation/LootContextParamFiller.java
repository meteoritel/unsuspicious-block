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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 战利品上下文参数填充器——按战利品表声明的 {@link LootContextParamSet} 填充合理的默认值，
 * 使模拟抽取能在不同表类型下正常执行。
 * <p>
 * 填充规则（决策 9 保留下来的通用正确性规则）：
 * <ul>
 *   <li><b>只填 paramSet 允许的参数</b>。{@code LootParams.Builder.create} 会拒绝 allowed 之外的
 *       参数并抛 {@code IllegalArgumentException}；无条件塞入 {@code ORIGIN} 正是 barter 表
 *       直接抛异常的原因（D8）。</li>
 *   <li><b>required 与 optional 都填</b>。过去只遍历 required，使 optional 参数在条件求值时缺失——
 *       例如 {@code LootItemEntityPropertyCondition} 遇到缺失的 THIS_ENTITY 会恒为假，
 *       把"没填"伪装成"条件不成立"。注："路径排除 ≠ 类型排除"，被追踪的
 *       {@code gameplay/panning/*} 声明了 {@code minecraft:block}，同样会经过这里。</li>
 *   <li>实体参数用 {@link SimulationFakePlayer}；钓鱼上下文的 THIS_ENTITY 用
 *       {@link SimulationFishingHook}（使 {@code in_open_water} 等浮标谓词可判定）。</li>
 * </ul>
 * paramSet 与本填充模型根本不相容（如 barter 不允许 ORIGIN）的情形由
 * {@code LootMechanismSupport} 在构建期拦下，不会走到这里。
 */
public final class LootContextParamFiller {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LootContextParamFiller() {
    }

    /**
     * 为模拟构建 LootParams。
     * <p>
     * 若存在完全未知的 required 参数，回退到仅 ORIGIN 的宽松 paramSet，并保留既有语义：
     * 依赖未填充参数的条件按原版逻辑失败，由上层把零出现的条目记为「未命中」而不是伪造确定值。
     */
    public static LootParams createForSimulation(ServerLevel level, LootContextParamSet paramSet,
                                                 SimulationProfile profile) {
        LootParams.Builder builder = new LootParams.Builder(level).withLuck(profile.luck());
        if (fillAllowed(builder, paramSet, level, profile)) {
            return builder.create(paramSet);
        }

        // 回退：存在完全未支持的 required 参数。构建仅含 ORIGIN 的宽松 paramSet；
        // ORIGIN 不被该 paramSet 允许时说明它与填充模型不相容，明确失败而不是伪造上下文。
        LOGGER.info("回退到宽松 paramSet 模拟（原 paramSet 存在未支持的 required 参数）");
        LootContextParamSet looseParamSet = LootContextParamSet.builder()
                .required(LootContextParams.ORIGIN)
                .build();
        return new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, profile.origin())
                .withLuck(profile.luck())
                .create(looseParamSet);
    }

    /**
     * 按 paramSet 的 allowed 集合填充 required 与 optional 参数。
     *
     * @return true 若全部 required 参数均已填充；false 若存在未支持的 required 参数
     */
    private static boolean fillAllowed(LootParams.Builder builder, LootContextParamSet paramSet,
                                       ServerLevel level, SimulationProfile profile) {
        // required 与 optional 都要填，但只填 paramSet 允许的——create 会拒绝 allowed 之外的参数
        Set<LootContextParam<?>> targets = new LinkedHashSet<>(paramSet.getRequired());
        targets.addAll(paramSet.getAllowed());
        targets.removeIf(param -> !paramSet.isAllowed(param));

        // 延迟构造虚拟玩家：仅当遇到实体参数时才创建，避免无实体参数表的无效开销
        SimulationFakePlayer fakePlayer = null;
        boolean allSupported = true;
        boolean originFilled = false;

        for (LootContextParam<?> param : targets) {
            if (param == LootContextParams.ORIGIN) {
                builder.withParameter(LootContextParams.ORIGIN, profile.origin());
                originFilled = true;
                continue;
            }
            if (isEntityParam(param)) {
                if (fakePlayer == null) {
                    fakePlayer = new SimulationFakePlayer(level.getServer(), level);
                }
                // 钓鱼上下文的 THIS_ENTITY 用假浮标填充（owner 为假玩家），
                // 使 entity_properties + fishing_hook + in_open_water 等条件在模拟中可判定
                if (param == LootContextParams.THIS_ENTITY && profile.fishingContext()) {
                    builder.withParameter(LootContextParams.THIS_ENTITY,
                            new SimulationFishingHook(fakePlayer, level));
                    continue;
                }
                fillEntityParam(builder, param, fakePlayer);
                continue;
            }
            if (!fillScalarParam(builder, param, profile)) {
                allSupported = false;
            }
        }

        if (!originFilled) {
            // 不能在不允许 ORIGIN 的上下文里塞 ORIGIN（barter 就是这么炸的）。这里直接失败，
            // 由调用方按"该表不可用"上报，而不是抛一个玩家看不懂的异常。
            LOGGER.warn("paramSet {} 不允许 ORIGIN 参数，无法填充模拟上下文", paramSet);
            allSupported = false;
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
