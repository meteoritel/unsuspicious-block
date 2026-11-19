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
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.SHIMMER_STATUS_ID;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_CRAFTER_NAME;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SEALED_ITEM;
import static com.meteorite.unsuspiciousblock.core.plugin.jade.JadeData.TAG_SHIMMER_DEADLINE;

/** Jade 服务端数据提供者，按请求读取封存内容和淘洗点状态截止时间。 */
public final class JadeServerProviders {
    private JadeServerProviders() {
    }

    /** 在 Jade 公共注册相位安装数据提供者。 */
    public static void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(SealedContentsServerDataProvider.INSTANCE, UnsuspiciousBlockEntity.class);
        registration.registerEntityDataProvider(ShimmerStatusServerProvider.INSTANCE, ShimmerEntity.class);
    }

    enum ShimmerStatusServerProvider implements IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (accessor.getEntity() instanceof ShimmerEntity shimmer) {
                shimmer.getNextTimedChangeAt().ifPresent(deadline -> data.putLong(TAG_SHIMMER_DEADLINE, deadline));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return SHIMMER_STATUS_ID;
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
