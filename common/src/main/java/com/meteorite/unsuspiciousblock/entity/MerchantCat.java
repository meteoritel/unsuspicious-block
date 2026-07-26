package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.merchant.CatMerchantSpawnData;
import com.meteorite.unsuspiciousblock.cat.merchant.CatMerchantTradeManager;
import com.meteorite.unsuspiciousblock.cat.merchant.TaggedMerchantOffer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;

/**
 * 猫猫商人——只向猫国挚友开放、库存由实体实例全服共享的临时猫国灵体。
 */
public class MerchantCat extends SpiritCat implements Merchant {
    @Nullable
    private Player tradingPlayer;
    private MerchantOffers offers = new MerchantOffers();

    public MerchantCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
    }

    // 每次生成时固定本实例的六条交易，直到实体消散。
    public void initializeTrades(ServerLevel level, int catBond) {
        this.offers = CatMerchantTradeManager.createOffers(level, this.getRandom(), catBond);
    }

    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!this.offers.isEmpty()) {
            tag.put("CatMerchantOffers", this.saveOffers());
        }
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("CatMerchantOffers", Tag.TAG_LIST)) {
            this.offers = this.loadOffers(tag.getList("CatMerchantOffers", Tag.TAG_COMPOUND));
        }
    }

    private ListTag saveOffers() {
        ListTag saved = new ListTag();
        for (MerchantOffer offer : this.offers) {
            ItemStack buy = offer.getCostA();
            ItemStack sell = offer.getResult();
            ResourceLocation buyId = BuiltInRegistries.ITEM.getKey(buy.getItem());
            ResourceLocation sellId = BuiltInRegistries.ITEM.getKey(sell.getItem());
            CompoundTag entry = new CompoundTag();
            entry.putString("BuyItem", buyId.toString());
            entry.putInt("BuyCount", buy.getCount());
            entry.putString("SellItem", sellId.toString());
            entry.putInt("SellCount", sell.getCount());
            entry.putInt("MaxUses", offer.getMaxUses());
            entry.putInt("Uses", offer.getUses());
            if (offer instanceof TaggedMerchantOffer tagged) {
                entry.putString("BuyTag", tagged.getAcceptedTagId().toString());
                entry.putInt("BuyTagCount", tagged.getAcceptedCount());
            }
            saved.add(entry);
        }
        return saved;
    }

    private MerchantOffers loadOffers(ListTag saved) {
        MerchantOffers loaded = new MerchantOffers();
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            ResourceLocation buyId = ResourceLocation.tryParse(entry.getString("BuyItem"));
            ResourceLocation sellId = ResourceLocation.tryParse(entry.getString("SellItem"));
            if (buyId == null || sellId == null) {
                continue;
            }
            Item buyItem = BuiltInRegistries.ITEM.get(buyId);
            Item sellItem = BuiltInRegistries.ITEM.get(sellId);
            int buyCount = Math.max(1, entry.getInt("BuyCount"));
            int sellCount = Math.max(1, entry.getInt("SellCount"));
            int maxUses = Math.max(1, entry.getInt("MaxUses"));
            ItemCost cost = new ItemCost(buyItem, buyCount);
            ItemStack result = new ItemStack(sellItem, sellCount);
            MerchantOffer offer;
            ResourceLocation tagId = ResourceLocation.tryParse(entry.getString("BuyTag"));
            if (tagId != null) {
                TagKey<Item> acceptedTag = TagKey.create(Registries.ITEM, tagId);
                offer = new TaggedMerchantOffer(cost, result, maxUses, acceptedTag,
                        Math.max(1, entry.getInt("BuyTagCount")));
            } else {
                offer = new MerchantOffer(cost, result, maxUses, 0, 0.05F);
            }
            int uses = Math.min(maxUses, Math.max(0, entry.getInt("Uses")));
            for (int used = 0; used < uses; used++) {
                offer.increaseUses();
            }
            loaded.add(offer);
        }
        return loaded;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public @NotNull InteractionResult mobInteract(@NotNull Player player, @NotNull InteractionHand hand) {
        if (!this.isAlive() || this.level().isClientSide) {
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || CatFavorManager.getCatBond(serverPlayer) < 100
                || !CatFavorManager.hasOwnedHandOfCat(serverPlayer)) {
            player.displayClientMessage(
                    Component.translatable("message.unsuspiciousblock.cat_merchant.unqualified"), true);
            return InteractionResult.CONSUME;
        }
        if (this.offers.isEmpty() && this.level() instanceof ServerLevel serverLevel) {
            this.initializeTrades(serverLevel, 100);
        }
        if (!this.offers.isEmpty()) {
            this.setTradingPlayer(player);
            this.openTradingScreen(player, this.getDisplayName(), 1);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        this.tradingPlayer = player;
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
        if (reason.shouldDestroy() && !this.level().isClientSide
                && this.level() instanceof ServerLevel serverLevel) {
            CatMerchantSpawnData.get(serverLevel).clearActive(this.getUUID());
        }
        super.remove(reason);
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes();
    }
}
