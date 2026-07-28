package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.merchant.MerchantCatSpawnData;
import com.meteorite.unsuspiciousblock.cat.merchant.MerchantCatTradeManager;
import com.meteorite.unsuspiciousblock.cat.merchant.TaggedMerchantOffer;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MerchantCatPhase;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritMovementState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 猫猫商人实体，在生成村庄内徘徊并向合格玩家提供实例共享库存交易。
 */
public class MerchantCat extends SpiritCat implements Merchant {
    private static final String NBT_OFFERS = "MerchantCatOffers";
    private static final String NBT_ACTIVITY_CENTER = "MerchantActivityCenter";
    private static final String NBT_BUY_ITEM = "BuyItem";
    private static final String NBT_BUY_COUNT = "BuyCount";
    private static final String NBT_SELL_ITEM = "SellItem";
    private static final String NBT_SELL_COUNT = "SellCount";
    private static final String NBT_MAX_USES = "MaxUses";
    private static final String NBT_USES = "Uses";
    private static final String NBT_BUY_TAG = "BuyTag";
    private static final String NBT_BUY_TAG_COUNT = "BuyTagCount";
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(MerchantCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PHASE_START_TICK =
            SynchedEntityData.defineId(MerchantCat.class, EntityDataSerializers.INT);
    private static final double ACTIVITY_RANGE = 24.0;
    private static final double INTEREST_MIN_RANGE = 8.0;
    private static final double INTEREST_MAX_RANGE = 16.0;
    private static final double ATTENTION_RANGE = 8.0;
    private static final double ACTIVE_PLAYER_RANGE = 48.0;
    private static final int MANIFEST_TICKS = 10;
    private static final int OBSERVE_TICKS = 20;
    private static final int INTEREST_MIN_TICKS = 80;
    private static final int INTEREST_MAX_TICKS = 160;
    private static final int PLAYER_SCAN_TICKS = 20;
    private static final int TRADE_REACTION_TICKS = 20;
    private static final int GUI_SOUND_COOLDOWN_TICKS = 20;
    private static final int FAREWELL_START_TICKS = 200;
    private static final int DISSIPATE_TICKS = 15;
    private static final int MAX_TRADE_GRACE_TICKS = 1200;

    @Nullable
    private Player tradingPlayer;
    private MerchantOffers offers = new MerchantOffers();
    @Nullable
    private BlockPos activityCenter;
    @Nullable
    private Vec3 interestPoint;
    @Nullable
    private UUID attentionPlayerUuid;
    private MerchantCatPhase phase = MerchantCatPhase.MANIFEST;
    private int phaseTicks;
    private int interestCooldown;
    private int playerScanCooldown;
    private int guiSoundCooldown;
    private int expiryGraceTicks;
    private boolean nearbyPlayer;
    private boolean tradeOccurred;
    @Nullable
    private UUID debugOwnerUuid;

    public MerchantCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, MerchantCatPhase.MANIFEST.ordinal());
        builder.define(DATA_PHASE_START_TICK, 0);
    }

    @Override
    protected void registerGoals() {
    }

    // 记录本次商人的村庄活动中心。
    public void configureActivityCenter(BlockPos center) {
        this.activityCenter = center.immutable();
        this.interestPoint = null;
        this.interestCooldown = 0;
        this.setPhase(MerchantCatPhase.MANIFEST);
    }

    // 每次生成时固定本实例的六条交易，直到实体消散。
    public void initializeTrades(ServerLevel level, int catBond) {
        this.offers = MerchantCatTradeManager.createOffers(level, this.getRandom(), catBond);
    }

    public MerchantCatPhase getMerchantPhase() {
        return MerchantCatPhase.fromOrdinal(this.entityData.get(DATA_PHASE));
    }

    public int getPhaseTicks() {
        return this.phaseTicks;
    }

    public BlockPos getActivityCenter() {
        return this.activityCenter == null ? this.blockPosition() : this.activityCenter;
    }

    // 调试商人只为绑定玩家绕过交易资格。
    public void configureDebugOwner(UUID playerUuid) {
        this.debugOwnerUuid = playerUuid;
    }

    // 调试命令强制切换职业阶段。
    public void forceDebugPhase(MerchantCatPhase phase) {
        this.setPhase(phase);
    }

    @Override
    public boolean shouldBeSaved() {
        return !this.isDebugDuty();
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!this.offers.isEmpty()) {
            tag.put(NBT_OFFERS, this.saveOffers());
        }
        if (this.activityCenter != null) {
            tag.putLong(NBT_ACTIVITY_CENTER, this.activityCenter.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(NBT_OFFERS, Tag.TAG_LIST)) {
            this.offers = this.loadOffers(tag.getList(NBT_OFFERS, Tag.TAG_COMPOUND));
        }
        this.activityCenter = tag.contains(NBT_ACTIVITY_CENTER, Tag.TAG_LONG)
                ? BlockPos.of(tag.getLong(NBT_ACTIVITY_CENTER)) : this.blockPosition();
        this.phase = this.isDutyActive() ? MerchantCatPhase.OBSERVE : MerchantCatPhase.MANIFEST;
        this.entityData.set(DATA_PHASE, this.phase.ordinal());
        this.entityData.set(DATA_PHASE_START_TICK, this.tickCount);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || this.isRemoved() || !this.isDutyActive()
                || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.activityCenter == null) {
            this.activityCenter = this.blockPosition();
        }
        this.phaseTicks++;
        if (this.interestCooldown > 0) {
            this.interestCooldown--;
        }
        if (this.playerScanCooldown > 0) {
            this.playerScanCooldown--;
        }
        if (this.guiSoundCooldown > 0) {
            this.guiSoundCooldown--;
        }
        this.validateTradingPlayer();
        if (this.tradingPlayer != null) {
            if (this.phase != MerchantCatPhase.TRADING) {
                this.setPhase(MerchantCatPhase.TRADING);
            }
            this.getFlightController().stop();
            this.getLookControl().setLookAt(this.tradingPlayer);
            return;
        }

        int remaining = this.getSpiritLifetimeRemaining();
        if (remaining > 0 && remaining <= FAREWELL_START_TICKS
                && this.phase != MerchantCatPhase.FAREWELL && this.phase != MerchantCatPhase.DISSIPATE) {
            this.setPhase(MerchantCatPhase.FAREWELL);
        }
        if (this.playerScanCooldown <= 0) {
            this.nearbyPlayer = level.getNearestPlayer(this, ACTIVE_PLAYER_RANGE) != null;
            this.playerScanCooldown = PLAYER_SCAN_TICKS;
            this.updateAttentionPlayer(level);
        }
        if (!this.nearbyPlayer && this.phase != MerchantCatPhase.FAREWELL
                && this.phase != MerchantCatPhase.DISSIPATE) {
            this.getFlightController().stop();
            return;
        }

        switch (this.phase) {
            case MANIFEST -> this.tickManifest(level);
            case OBSERVE -> this.tickObserve();
            case ROAM -> this.tickRoam(level);
            case ATTEND -> this.tickAttend(level);
            case TRADING -> this.getFlightController().stop();
            case TRADE_REACTION -> this.tickTradeReaction(level);
            case RETURN -> this.tickReturn();
            case FAREWELL -> this.tickFarewell(level, remaining);
            case DISSIPATE -> this.tickDissipate(level);
        }
    }

    private void tickManifest(ServerLevel level) {
        this.getFlightController().stop();
        if (this.phaseTicks == 1) {
            level.sendParticles(ParticleTypes.END_ROD,
                    this.getX(), this.getY() + 0.3, this.getZ(), 8, 0.3, 0.25, 0.3, 0.03);
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.45F, 1.0F);
        }
        if (this.phaseTicks >= MANIFEST_TICKS) {
            this.setPhase(MerchantCatPhase.OBSERVE);
        }
    }

    private void tickObserve() {
        this.getFlightController().stop();
        if (this.phaseTicks >= OBSERVE_TICKS) {
            this.setPhase(this.attentionPlayerUuid == null ? MerchantCatPhase.ROAM : MerchantCatPhase.ATTEND);
        }
    }

    private void tickRoam(ServerLevel level) {
        if (this.attentionPlayerUuid != null) {
            this.setPhase(MerchantCatPhase.ATTEND);
            return;
        }
        if (this.distanceToSqr(Vec3.atCenterOf(this.activityCenter)) > ACTIVITY_RANGE * ACTIVITY_RANGE) {
            this.setPhase(MerchantCatPhase.RETURN);
            return;
        }
        if (this.interestPoint == null || this.interestCooldown <= 0
                || this.distanceToSqr(this.interestPoint) <= 1.0) {
            this.interestPoint = this.selectInterestPoint(level);
            this.interestCooldown = this.getRandom().nextIntBetweenInclusive(
                    INTEREST_MIN_TICKS, INTEREST_MAX_TICKS);
        }
        if (this.interestPoint != null) {
            this.getFlightController().moveToward(this.interestPoint, 0.22, false, 40);
        } else {
            this.getFlightController().stop();
        }
    }

    private void tickAttend(ServerLevel level) {
        ServerPlayer player = this.resolveAttentionPlayer(level);
        if (player == null) {
            this.attentionPlayerUuid = null;
            this.setPhase(MerchantCatPhase.ROAM);
            return;
        }
        this.getFlightController().stop();
        this.getLookControl().setLookAt(player);
    }

    private void tickTradeReaction(ServerLevel level) {
        this.getFlightController().stop();
        if (this.phaseTicks == 1 && this.tradeOccurred) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    this.getX(), this.getY() + 0.5, this.getZ(), 5, 0.25, 0.2, 0.25, 0.0);
        }
        if (this.phaseTicks >= TRADE_REACTION_TICKS) {
            this.tradeOccurred = false;
            this.setPhase(MerchantCatPhase.ROAM);
        }
    }

    private void tickReturn() {
        Vec3 center = this.getCenterHoverPosition();
        if (this.distanceToSqr(center) <= 2.25) {
            this.getFlightController().stop();
            this.setPhase(MerchantCatPhase.ROAM);
        } else {
            this.getFlightController().moveToward(center, 0.3, false, 40);
        }
    }

    private void tickFarewell(ServerLevel level, int remaining) {
        Vec3 center = this.getCenterHoverPosition();
        if (this.phaseTicks == 1) {
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.35F, 0.85F);
        }
        if (this.distanceToSqr(center) > 4.0) {
            this.getFlightController().moveToward(center, 0.3, false, 40);
        } else {
            this.getFlightController().stop();
        }
        if (remaining <= DISSIPATE_TICKS) {
            this.setPhase(MerchantCatPhase.DISSIPATE);
        }
    }

    private void tickDissipate(ServerLevel level) {
        if (this.phaseTicks == 1) {
            level.sendParticles(ParticleTypes.END_ROD,
                    this.getX(), this.getY() + 0.3, this.getZ(), 12, 0.35, 0.35, 0.35, 0.08);
        }
        this.setDeltaMovement(0.0, 0.04, 0.0);
        if (this.phaseTicks >= DISSIPATE_TICKS) {
            this.discard();
        }
    }

    private void validateTradingPlayer() {
        if (this.tradingPlayer != null && (!this.tradingPlayer.isAlive()
                || this.tradingPlayer.level() != this.level()
                || this.distanceToSqr(this.tradingPlayer) > 16.0 * 16.0)) {
            this.setTradingPlayer(null);
        }
    }

    private void updateAttentionPlayer(ServerLevel level) {
        ServerPlayer previous = this.resolveAttentionPlayer(level);
        ServerPlayer nearest = level.getEntitiesOfClass(ServerPlayer.class,
                        this.getBoundingBox().inflate(ATTENTION_RANGE), this::isQualified)
                .stream()
                .min(Comparator.comparingDouble(this::distanceToSqr))
                .orElse(null);
        this.attentionPlayerUuid = nearest == null ? null : nearest.getUUID();
        if (nearest != null && previous != nearest && this.guiSoundCooldown <= 0) {
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.25F, 1.2F);
            this.guiSoundCooldown = GUI_SOUND_COOLDOWN_TICKS;
        }
    }

    @Nullable
    private ServerPlayer resolveAttentionPlayer(ServerLevel level) {
        return this.attentionPlayerUuid != null
                && level.getPlayerByUUID(this.attentionPlayerUuid) instanceof ServerPlayer player
                && player.isAlive() && player.distanceToSqr(this) <= ATTENTION_RANGE * ATTENTION_RANGE
                ? player : null;
    }

    private Vec3 selectInterestPoint(ServerLevel level) {
        AABB villageArea = AABB.ofSize(Vec3.atCenterOf(this.activityCenter),
                ACTIVITY_RANGE * 2.0, 16.0, ACTIVITY_RANGE * 2.0);
        List<Villager> professionals = level.getEntitiesOfClass(Villager.class, villageArea,
                villager -> villager.isAlive()
                        && villager.getVillagerData().getProfession() != VillagerProfession.NONE);
        if (!professionals.isEmpty() && this.getRandom().nextFloat() < 0.4F) {
            Villager villager = professionals.get(this.getRandom().nextInt(professionals.size()));
            return villager.position().add(0.0, 1.5, 0.0);
        }
        if (this.getRandom().nextFloat() < 0.2F) {
            return this.getCenterHoverPosition();
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = this.getRandom().nextDouble() * Math.PI * 2.0;
            double radius = INTEREST_MIN_RANGE
                    + this.getRandom().nextDouble() * (INTEREST_MAX_RANGE - INTEREST_MIN_RANGE);
            int x = Mth.floor(this.activityCenter.getX() + Math.cos(angle) * radius);
            int z = Mth.floor(this.activityCenter.getZ() + Math.sin(angle) * radius);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos surface = new BlockPos(x, y, z);
            if (level.isVillage(surface) && isOpen(level, surface)) {
                return new Vec3(x + 0.5, y + 1.5, z + 0.5);
            }
        }
        return this.getCenterHoverPosition();
    }

    private Vec3 getCenterHoverPosition() {
        return Vec3.atCenterOf(this.activityCenter).add(0.0, 1.0, 0.0);
    }

    private boolean isQualified(ServerPlayer player) {
        return this.isDebugDuty() && player.getUUID().equals(this.debugOwnerUuid)
                || CatFavorManager.getCatBond(player) >= 100 && CatFavorManager.hasOwnedHandOfCat(player);
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
    }

    private void setPhase(MerchantCatPhase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
        this.entityData.set(DATA_PHASE, phase.ordinal());
        this.entityData.set(DATA_PHASE_START_TICK, this.tickCount);
    }

    @Override
    protected boolean shouldExpireNow() {
        if (this.tradingPlayer != null || this.phase == MerchantCatPhase.TRADE_REACTION
                || this.phase == MerchantCatPhase.FAREWELL || this.phase == MerchantCatPhase.DISSIPATE) {
            return ++this.expiryGraceTicks > MAX_TRADE_GRACE_TICKS;
        }
        if (this.level() instanceof ServerLevel level
                && level.getNearestPlayer(this, ACTIVE_PLAYER_RANGE) != null) {
            this.setPhase(MerchantCatPhase.DISSIPATE);
            return ++this.expiryGraceTicks > DISSIPATE_TICKS;
        }
        return true;
    }

    @Override
    public float getRenderAlphaProgress(float partialTick) {
        float alpha = super.getRenderAlphaProgress(partialTick);
        return this.getSpiritMovementState() == SpiritMovementState.PHASE ? alpha * 0.55F : alpha;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public @NotNull InteractionResult mobInteract(@NotNull Player player, @NotNull InteractionHand hand) {
        if (!this.isDutyActive()) {
            return InteractionResult.PASS;
        }
        if (!this.isAlive() || this.level().isClientSide) {
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !this.isQualified(serverPlayer)) {
            player.displayClientMessage(
                    Component.translatable("message.unsuspiciousblock.merchant_cat.unqualified"), true);
            return InteractionResult.CONSUME;
        }
        if (this.tradingPlayer != null && this.tradingPlayer != player) {
            player.displayClientMessage(
                    Component.translatable("message.unsuspiciousblock.merchant_cat.busy"), true);
            return InteractionResult.CONSUME;
        }
        if (this.offers.isEmpty() && this.level() instanceof ServerLevel serverLevel) {
            this.initializeTrades(serverLevel, 100);
        }
        if (!this.offers.isEmpty()) {
            this.tradeOccurred = false;
            this.setTradingPlayer(player);
            if (this.level() instanceof ServerLevel level && this.guiSoundCooldown <= 0) {
                level.playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.CAT_AMBIENT, SoundSource.NEUTRAL, 0.4F, 1.1F);
                this.guiSoundCooldown = GUI_SOUND_COOLDOWN_TICKS;
            }
            this.openTradingScreen(player, this.getDisplayName(), 1);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        Player previous = this.tradingPlayer;
        this.tradingPlayer = player;
        if (!this.level().isClientSide) {
            if (player != null) {
                this.setPhase(MerchantCatPhase.TRADING);
            } else if (previous != null) {
                this.setPhase(this.tradeOccurred
                        ? MerchantCatPhase.TRADE_REACTION : MerchantCatPhase.ATTEND);
            }
        }
    }

    @Override
    public @Nullable Player getTradingPlayer() {
        return this.tradingPlayer;
    }

    @Override
    public @NotNull MerchantOffers getOffers() {
        return this.offers;
    }

    @Override
    public void overrideOffers(@NotNull MerchantOffers offers) {
        this.offers = offers;
    }

    @Override
    public void notifyTrade(@NotNull MerchantOffer offer) {
        offer.increaseUses();
        this.tradeOccurred = true;
    }

    @Override
    public void notifyTradeUpdated(@NotNull ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public @NotNull SoundEvent getNotifyTradeSound() {
        return SoundEvents.CAT_AMBIENT;
    }

    @Override
    public boolean isClientSide() {
        return this.level().isClientSide;
    }

    @Override
    public void remove(Entity.@NotNull RemovalReason reason) {
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            MerchantCatSpawnData.get(serverLevel).clearActive(this.getUUID());
        }
        super.remove(reason);
    }

    private ListTag saveOffers() {
        ListTag saved = new ListTag();
        for (MerchantOffer offer : this.offers) {
            ItemStack buy = offer.getCostA();
            ItemStack sell = offer.getResult();
            ResourceLocation buyId = BuiltInRegistries.ITEM.getKey(buy.getItem());
            ResourceLocation sellId = BuiltInRegistries.ITEM.getKey(sell.getItem());
            CompoundTag entry = new CompoundTag();
            entry.putString(NBT_BUY_ITEM, buyId.toString());
            entry.putInt(NBT_BUY_COUNT, buy.getCount());
            entry.putString(NBT_SELL_ITEM, sellId.toString());
            entry.putInt(NBT_SELL_COUNT, sell.getCount());
            entry.putInt(NBT_MAX_USES, offer.getMaxUses());
            entry.putInt(NBT_USES, offer.getUses());
            if (offer instanceof TaggedMerchantOffer tagged) {
                entry.putString(NBT_BUY_TAG, tagged.getAcceptedTagId().toString());
                entry.putInt(NBT_BUY_TAG_COUNT, tagged.getAcceptedCount());
            }
            saved.add(entry);
        }
        return saved;
    }

    private MerchantOffers loadOffers(ListTag saved) {
        MerchantOffers loaded = new MerchantOffers();
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            ResourceLocation buyId = ResourceLocation.tryParse(entry.getString(NBT_BUY_ITEM));
            ResourceLocation sellId = ResourceLocation.tryParse(entry.getString(NBT_SELL_ITEM));
            if (buyId == null || sellId == null) {
                continue;
            }
            Item buyItem = BuiltInRegistries.ITEM.get(buyId);
            Item sellItem = BuiltInRegistries.ITEM.get(sellId);
            int buyCount = Math.max(1, entry.getInt(NBT_BUY_COUNT));
            int sellCount = Math.max(1, entry.getInt(NBT_SELL_COUNT));
            int maxUses = Math.max(1, entry.getInt(NBT_MAX_USES));
            ItemCost cost = new ItemCost(buyItem, buyCount);
            ItemStack result = new ItemStack(sellItem, sellCount);
            MerchantOffer offer;
            ResourceLocation tagId = ResourceLocation.tryParse(entry.getString(NBT_BUY_TAG));
            if (tagId != null) {
                TagKey<Item> acceptedTag = TagKey.create(Registries.ITEM, tagId);
                offer = new TaggedMerchantOffer(cost, result, maxUses, acceptedTag,
                        Math.max(1, entry.getInt(NBT_BUY_TAG_COUNT)));
            } else {
                offer = new MerchantOffer(cost, result, maxUses, 0, 0.05F);
            }
            int uses = Math.min(maxUses, Math.max(0, entry.getInt(NBT_USES)));
            for (int used = 0; used < uses; used++) {
                offer.increaseUses();
            }
            loaded.add(offer);
        }
        return loaded;
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes();
    }
}
