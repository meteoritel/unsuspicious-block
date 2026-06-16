package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.entity.ai.GhostCatGiftGoal;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 幽灵猫 —— 继承原版猫，但半透明、发光、无实体碰撞、不可交互。
 * 由「古国往礼」被动召唤，仅执行向目标玩家送礼后自然消散的行为。
 */
public class GhostCat extends Cat {

    // 送礼目标玩家 UUID（仅服务端使用）
    @Nullable
    private UUID giftTargetUuid;
    // 礼物战利品表
    @Nullable
    private ResourceKey<LootTable> giftLootTable;

    public GhostCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
        // 召唤而来，不参与自然刷新清理
        this.setPersistenceRequired();
    }

    // 仅注册「送礼并消散」AI，剔除原版猫的全部其他行为
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new GhostCatGiftGoal(this));
    }

    // 设置送礼目标与礼物战利品表
    public void assignGift(UUID targetUuid, ResourceKey<LootTable> lootTable) {
        this.giftTargetUuid = targetUuid;
        this.giftLootTable = lootTable;
    }

    @Nullable
    public UUID getGiftTargetUuid() {
        return this.giftTargetUuid;
    }

    @Nullable
    public ResourceKey<LootTable> getGiftLootTable() {
        return this.giftLootTable;
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
