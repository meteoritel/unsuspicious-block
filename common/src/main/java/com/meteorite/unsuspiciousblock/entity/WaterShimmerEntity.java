package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariants;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/***
 * 闪烁的光·水域变体——依附水方块与河水冻结后的普通冰，沿河流群系与海平面生成。
 * <p>
 * 本类只回答「我是哪个变体」，机制骨架、账本登记与全部表现参数都继承自 {@link ShimmerEntity}。
 * 请注意冰面依附点是刻意不可淘洗的，原因见 {@code docs/plan/panning-variants.md} §3.9。
 */
public class WaterShimmerEntity extends ShimmerEntity {
    // 构造器参数放宽为 ? extends WaterShimmerEntity，与原版子类实体的写法一致
    public WaterShimmerEntity(EntityType<? extends WaterShimmerEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public ShimmerVariant getVariant() {
        return ShimmerVariants.WATER;
    }
}
