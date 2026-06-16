package com.meteorite.unsuspiciousblock.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * 幽灵猫 —— 继承原版猫行为，但半透明、发光、无实体碰撞、不可交互
 */
public class GhostCat extends Cat {

    public GhostCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    // 不可被推动
    @Override
    public boolean isPushable() {
        return false;
    }

    // 不可被玩家选中/右键交互
    @Override
    public boolean isPickable() {
        return false;
    }

    // 无实体碰撞
    @Override
    public boolean canCollideWith(@NotNull Entity other) {
        return false;
    }

    // 不推动其他实体
    @Override
    public void push(@NotNull Entity entity) {
    }

    // 不受任何伤害
    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        return false;
    }

    // 不可被敌对生物攻击
    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return Cat.createAttributes();
    }
}
