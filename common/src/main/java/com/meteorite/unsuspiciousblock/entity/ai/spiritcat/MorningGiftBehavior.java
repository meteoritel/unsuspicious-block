package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.UUID;

/**
 * 晨礼行为 —— 玩家晨礼时恩惠≥80 触发，幽灵猫送来古国往礼。
 * <p>
 * 完整阶段：显现(10t) → 接近 → 致意(20t) → 赠礼 → 消散(15t)。
 * 总寿命上限 600t（30 秒）。
 */
public class MorningGiftBehavior implements MessengerCatBehavior {

    // ========== 阶段时长（tick） ==========
    private static final int MANIFEST_TICKS = 10;
    private static final int GREET_TICKS = 20;
    private static final int DISSIPATE_TICKS = 15;
    private static final int MAX_LIFETIME_TICKS = 600;
    // 致意粒子持续 tick 数（致意阶段前半段）
    private static final int GREET_PARTICLE_TICKS = 8;

    // 送礼目标与战利品表
    private final UUID targetUuid;
    private final ResourceKey<LootTable> lootTable;

    public MorningGiftBehavior(UUID targetUuid, ResourceKey<LootTable> lootTable) {
        this.targetUuid = targetUuid;
        this.lootTable = lootTable;
    }

    @Override
    public UUID getTargetUuid() {
        return this.targetUuid;
    }

    @Override
    public ResourceKey<LootTable> getLootTable() {
        return this.lootTable;
    }

    @Override
    public int getManifestDuration() {
        return MANIFEST_TICKS;
    }

    @Override
    public int getGreetDuration() {
        return GREET_TICKS;
    }

    @Override
    public int getDissipateDuration() {
        return DISSIPATE_TICKS;
    }

    @Override
    public int getMaxLifetime() {
        return MAX_LIFETIME_TICKS;
    }

    @Override
    public boolean shouldGreet() {
        return true;
    }

    @Override
    public boolean shouldDeliverGift() {
        return this.lootTable != null;
    }

    // ========== 阶段副作用 ==========

    // 显现：脚下 end_rod 汇聚 + 细微猫嘶
    @Override
    public void onManifestStart(MessengerCat cat, ServerLevel level) {
        level.sendParticles(ParticleTypes.END_ROD,
                cat.getX(), cat.getY() + 0.1, cat.getZ(),
                8, 0.3, 0.05, 0.3, 0.02);
        level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                SoundEvents.CAT_HISS, SoundSource.NEUTRAL, 0.3F, 1.5F);
    }

    // 接近：身后 soul_fire_flame 拖尾
    @Override
    public void onApproachTick(MessengerCat cat, ServerLevel level) {
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                cat.getX(), cat.getY() + 0.2, cat.getZ(),
                1, 0.0, 0.0, 0.0, 0.0);
    }

    // 致意：前 8 tick 撒心型粒子，首 tick 播放 purr
    @Override
    public void onGreetTick(MessengerCat cat, ServerLevel level, int ticksInPhase) {
        if (ticksInPhase < GREET_PARTICLE_TICKS) {
            level.sendParticles(ParticleTypes.HEART,
                    cat.getX(), cat.getY() + 0.6, cat.getZ(),
                    1, 0.2, 0.1, 0.2, 0.0);
        }
        if (ticksInPhase == 1) {
            level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                    SoundEvents.CAT_PURR, SoundSource.NEUTRAL, 0.4F, 1.2F);
        }
    }

    // 赠礼：翻滚战利品表掷出物品 + bell 声
    @Override
    public void onDeliver(MessengerCat cat, ServerLevel level) {
        ResourceKey<LootTable> lootKey = this.lootTable;
        if (lootKey == null) {
            return;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(lootKey);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, cat.position())
                .withParameter(LootContextParams.THIS_ENTITY, cat)
                .create(LootContextParamSets.GIFT);
        boolean delivered = false;
        for (ItemStack stack : table.getRandomItems(params)) {
            level.addFreshEntity(new ItemEntity(level,
                    cat.getX(), cat.getY() + 0.3, cat.getZ(), stack));
            delivered = true;
        }
        if (delivered && level.getPlayerByUUID(this.targetUuid) instanceof ServerPlayer player) {
            CatFavorManager.onMessengerGiftDelivered(player);
        }
        level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 0.5F, 1.3F);
    }

    // 消散：end_rod 向上爆散 + 渐远猫叫
    @Override
    public void onDissipateStart(MessengerCat cat, ServerLevel level) {
        level.sendParticles(ParticleTypes.END_ROD,
                cat.getX(), cat.getY() + 0.3, cat.getZ(),
                16, 0.4, 0.4, 0.4, 0.1);
        level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.3F, 0.8F);
    }
}
