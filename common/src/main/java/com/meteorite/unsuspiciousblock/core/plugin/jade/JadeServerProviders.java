package com.meteorite.unsuspiciousblock.core.plugin.jade;

import com.meteorite.unsuspiciousblock.core.block.SealedContentsDisplay;
import com.meteorite.unsuspiciousblock.core.blockentity.UnsuspiciousBlockEntity;
import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.IWailaCommonRegistration;

import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SEALED_CONTENTS_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SHIMMER_LIFETIME_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SHIMMER_REMAINING;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SEALED_ITEM;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_CRAFTER_NAME;

/** Jade 服务端数据提供者，按请求读取封存内容和淘洗点寿命。 */
public final class JadeServerProviders {
    private JadeServerProviders() {
    }

    /** 在 Jade 公共注册相位安装数据提供者。 */
    public static void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(SealedContentsServerDataProvider.INSTANCE, UnsuspiciousBlockEntity.class);
        registration.registerEntityDataProvider(ShimmerLifetimeServerProvider.INSTANCE, ShimmerEntity.class);
    }

    enum ShimmerLifetimeServerProvider implements IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (accessor.getEntity() instanceof ShimmerEntity shimmer) {
                data.putLong(TAG_SHIMMER_REMAINING, shimmer.getSpawnSource().hasLifetime()
                        ? Math.max(0L, shimmer.getExpiresAt() - shimmer.level().getGameTime()) : -1L);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return SHIMMER_LIFETIME_ID;
        }
    }

    enum SealedContentsServerDataProvider implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof UnsuspiciousBlockEntity blockEntity)) {
                return;
            }

            ItemStack sealedItem = blockEntity.unsuspiciousblock$getItem().copy();
            data.put(TAG_SEALED_ITEM,
                    accessor.encodeAsNbt(ItemStack.OPTIONAL_STREAM_CODEC, sealedItem));

            blockEntity.getCrafter()
                    .map(SealedContentsDisplay::displayCrafterName)
                    .ifPresent(name -> data.putString(TAG_CRAFTER_NAME, name));
        }

        @Override
        public ResourceLocation getUid() {
            return SEALED_CONTENTS_ID;
        }
    }
}
