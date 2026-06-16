package com.meteorite.unsuspiciousblock.entity.ai;

import com.meteorite.unsuspiciousblock.entity.GhostCat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 幽灵猫送礼 AI —— 寻路靠近目标玩家，到达后掷出「古国往礼」战利品并自然消散。
 * 若长时间无法到达则在原地交付礼物后消散，避免幽灵猫滞留。
 */
public class GhostCatGiftGoal extends Goal {

    // 送达判定距离的平方（约 3 格）
    private static final double DELIVER_DISTANCE_SQR = 9.0;
    // 最长存活时间（tick），超时则原地交付并消散
    private static final int MAX_LIFETIME_TICKS = 200;
    // 移动速度倍率
    private static final double MOVE_SPEED = 1.1;

    private final GhostCat cat;
    private Player target;
    private int ticks;

    public GhostCatGiftGoal(GhostCat cat) {
        this.cat = cat;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.resolveTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.cat.isRemoved() && this.target != null && this.target.isAlive();
    }

    @Override
    public void start() {
        this.ticks = 0;
        if (this.target != null) {
            this.cat.getNavigation().moveTo(this.target, MOVE_SPEED);
        }
    }

    @Override
    public void tick() {
        this.ticks++;
        if (this.target != null) {
            this.cat.getLookControl().setLookAt(this.target);
            this.cat.getNavigation().moveTo(this.target, MOVE_SPEED);
        }
        boolean reached = this.target != null && this.cat.distanceToSqr(this.target) <= DELIVER_DISTANCE_SQR;
        if (reached || this.ticks >= MAX_LIFETIME_TICKS) {
            this.deliverGift();
            this.cat.discard();
        }
    }

    // 解析目标玩家（服务端），无效时返回 null
    private Player resolveTarget() {
        UUID uuid = this.cat.getGiftTargetUuid();
        if (uuid == null || this.cat.getGiftLootTable() == null) {
            return null;
        }
        if (!(this.cat.level() instanceof ServerLevel level)) {
            return null;
        }
        Player player = level.getPlayerByUUID(uuid);
        if (player == null || !player.isAlive()) {
            return null;
        }
        this.target = player;
        return player;
    }

    // 翻滚战利品表并在幽灵猫位置掷出礼物
    private void deliverGift() {
        ResourceKey<LootTable> lootKey = this.cat.getGiftLootTable();
        if (lootKey == null || !(this.cat.level() instanceof ServerLevel level)) {
            return;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(lootKey);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, this.cat.position())
                .withParameter(LootContextParams.THIS_ENTITY, this.cat)
                .create(LootContextParamSets.GIFT);
        for (ItemStack stack : table.getRandomItems(params)) {
            level.addFreshEntity(new ItemEntity(level, this.cat.getX(), this.cat.getY(), this.cat.getZ(), stack));
        }
    }
}
