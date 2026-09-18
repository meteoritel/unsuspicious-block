package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariants;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/***
 * 幽微的光——依附下界岩浆的淘洗点变体。
 * <p>
 * 本类只回答「我是哪个变体」，机制骨架、账本登记与全部表现参数都继承自 {@link ShimmerEntity}。
 * 它只由世界生成地物投放下界，不做运行时自然生成，因此没有每维度数量上限。
 * 岩浆没有碰撞箱，玩家可以正常瞄准并淘洗海面中央的点（与冰面依附点相反）。
 */
public class GlimmerEntity extends ShimmerEntity {
    public GlimmerEntity(EntityType<? extends GlimmerEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public ShimmerVariant getVariant() {
        return ShimmerVariants.GLIMMER;
    }
}
