package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FishingHook;

/**
 * 模拟用虚拟钓鱼浮标--钓鱼类战利品表模拟时填充 THIS_ENTITY。
 * <p>
 * 真实钓鱼掉落的 THIS_ENTITY 是 {@link FishingHook}，{@code entity_properties} +
 * {@code fishing_hook} 子谓词（如 in_open_water）要求实体为浮标并调用
 * {@link FishingHook#isOpenWaterFishing()}。模拟期用假浮标填充使这类条件可判定，
 * 概率从"条件概率(?)"变为可计算的估算值。
 * owner 为 {@link SimulationFakePlayer}，满足 {@code getPlayerOwner()} 类型的条件。
 */
public final class SimulationFishingHook extends FishingHook {

    public SimulationFishingHook(SimulationFakePlayer owner, ServerLevel level) {
        super(owner, level, 0, 0);
        // 构造器会经 setOwner -> updateOwnerInfo 把 this 写入 owner.fishing，
        // 还原为 null，避免虚拟玩家持有模拟期浮标引用
        owner.fishing = null;
    }

    @Override
    public boolean isOpenWaterFishing() {
        // 模拟假设处于开放水域；显式返回 true，不依赖 openWater 字段的默认值语义
        return true;
    }

    @Override
    public void tick() {
        // 虚拟浮标不参与 tick，避免状态流转、粒子与声音等副作用
    }
}
