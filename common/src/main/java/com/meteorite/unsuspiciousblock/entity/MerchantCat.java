package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.merchant.MerchantCatSpawnData;
import com.meteorite.unsuspiciousblock.cat.merchant.MerchantCatTradeManager;
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
 * 商人猫猫——只向猫国挚友开放、库存由实体实例全服共享的临时猫国灵体。
 */
public class MerchantCat extends SpiritCat implements Merchant {
    // NBT 键常量
    private static final String NBT_OFFERS = "MerchantCatOffers";
    private static final String NBT_BUY_ITEM = "BuyItem";
    private static final String NBT_BUY_COUNT = "BuyCount";
    private static final String NBT_SELL_ITEM = "SellItem";
    private static final String NBT_SELL_COUNT = "SellCount";
    private static final String NBT_MAX_USES = "MaxUses";
    private static final String NBT_USES = "Uses";
    private static final String NBT_BUY_TAG = "BuyTag";
    private static final String NBT_BUY_TAG_COUNT = "BuyTagCount";

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
        this.offers = MerchantCatTradeManager.createOffers(level, this.getRandom(), catBond);
    }

    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!this.offers.isEmpty()) {
            tag.put(NBT_OFFERS, this.saveOffers());
        }
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(NBT_OFFERS, Tag.TAG_LIST)) {
            this.offers = this.loadOffers(tag.getList(NBT_OFFERS, Tag.TAG_COMPOUND));
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
            MerchantCatSpawnData.get(serverLevel).clearActive(this.getUUID());
        }
        super.remove(reason);
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return SpiritCat.createSpiritAttributes();
    }
}
