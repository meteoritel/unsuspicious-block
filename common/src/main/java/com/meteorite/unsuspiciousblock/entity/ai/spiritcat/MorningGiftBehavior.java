package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 猫国晨礼配送行为，负责礼物内容、职业音效和配送成功后的九命结算。
 */
public class MorningGiftBehavior implements MessengerCatBehavior {
    private static final int MANIFEST_TICKS = 10;
    private static final int GREET_TICKS = 20;
    private static final int RELOCATE_TICKS = 20;
    private static final int FAREWELL_TICKS = 20;
    private static final int DISSIPATE_TICKS = 15;
    private static final int GREET_PARTICLE_TICKS = 8;
    private static final int PICKUP_DELAY_TICKS = 10;

    private final UUID targetUuid;
    @Nullable
    private final UUID guideUuid;
    private final ResourceKey<LootTable> lootTable;
    private final boolean progressionEnabled;

    public MorningGiftBehavior(UUID targetUuid, @Nullable UUID guideUuid,
                               ResourceKey<LootTable> lootTable, boolean progressionEnabled) {
        this.targetUuid = targetUuid;
        this.guideUuid = guideUuid;
        this.lootTable = lootTable;
        this.progressionEnabled = progressionEnabled;
    }

    @Override
    public UUID getTargetUuid() {
        return this.targetUuid;
    }

    @Override
    public @Nullable UUID getGuideUuid() {
        return this.guideUuid;
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
    public int getRelocateDuration() {
        return RELOCATE_TICKS;
    }

    @Override
    public int getFarewellDuration() {
        return FAREWELL_TICKS;
    }

    @Override
    public int getMaxLifetime() {
        return Services.SPIRIT_CAT_CONFIG.getMessengerLifetimeTicks();
    }

    @Override
    public boolean shouldGreet() {
        return true;
    }

    @Override
    public boolean shouldDeliverGift() {
        return this.lootTable != null;
    }

    // 显现：低密度末地烛粒子与一次猫叫。
    @Override
    public void onManifestStart(MessengerCat cat, ServerLevel level) {
        level.sendParticles(ParticleTypes.END_ROD,
                cat.getX(), cat.getY() + 0.1, cat.getZ(),
                8, 0.3, 0.05, 0.3, 0.02);
        level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.45F, 1.2F);
    }

    // 接近：保留稀疏灵魂火拖尾。
    @Override
    public void onApproachTick(MessengerCat cat, ServerLevel level) {
        if (cat.tickCount % 3 == 0) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    cat.getX(), cat.getY() + 0.2, cat.getZ(),
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // 致意：短暂心形粒子并播放呼噜声。
    @Override
    public void onGreetTick(MessengerCat cat, ServerLevel level, int ticksInPhase) {
        if (ticksInPhase <= GREET_PARTICLE_TICKS && ticksInPhase % 2 == 0) {
            level.sendParticles(ParticleTypes.HEART,
                    cat.getX(), cat.getY() + 0.6, cat.getZ(),
                    1, 0.2, 0.1, 0.2, 0.0);
        }
        if (ticksInPhase == 1) {
            level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                    SoundEvents.CAT_PURR, SoundSource.NEUTRAL, 0.45F, 1.15F);
        }
    }

    // 投放真实 Loot Table 礼物，并让物品轻轻飞向玩家脚边。
    @Override
    public boolean onDeliver(MessengerCat cat, ServerLevel level) {
        if (this.lootTable == null) {
            return false;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(this.lootTable);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, cat.position())
                .withParameter(LootContextParams.THIS_ENTITY, cat)
                .create(LootContextParamSets.GIFT);
        ServerPlayer player = level.getPlayerByUUID(this.targetUuid) instanceof ServerPlayer serverPlayer
                ? serverPlayer : null;
        Vec3 destination = player == null ? cat.position() : player.position().add(0.0, 0.25, 0.0);
        boolean delivered = false;
        for (ItemStack stack : table.getRandomItems(params)) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemEntity item = new ItemEntity(level, cat.getX(), cat.getY() + 0.3, cat.getZ(), stack);
            Vec3 direction = destination.subtract(item.position());
            if (direction.lengthSqr() > 0.0001) {
                item.setDeltaMovement(direction.normalize().scale(0.18).add(0.0, 0.08, 0.0));
            }
            item.setPickUpDelay(PICKUP_DELAY_TICKS);
            delivered |= level.addFreshEntity(item);
        }
        if (delivered && this.progressionEnabled && player != null) {
            CatFavorManager.onMessengerGiftDelivered(player);
        }
        if (delivered) {
            level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                    SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 0.5F, 1.3F);
        }
        return delivered;
    }

    // 成功投放后播放轻柔告别猫叫。
    @Override
    public void onFarewellStart(MessengerCat cat, ServerLevel level) {
        level.playSound(null, cat.getX(), cat.getY(), cat.getZ(),
                SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.3F, 1.35F);
    }

    // 消散：末地烛粒子上浮爆散；失败流程也能获得明确结束反馈。
    @Override
    public void onDissipateStart(MessengerCat cat, ServerLevel level) {
        level.sendParticles(ParticleTypes.END_ROD,
                cat.getX(), cat.getY() + 0.3, cat.getZ(),
                12, 0.35, 0.35, 0.35, 0.08);
    }
}
