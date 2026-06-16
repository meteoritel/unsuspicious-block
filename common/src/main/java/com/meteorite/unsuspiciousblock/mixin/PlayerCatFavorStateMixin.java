package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorStateHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为玩家实体附加猫之恩惠状态并负责存档。
 */
@Mixin(Player.class)
public abstract class PlayerCatFavorStateMixin implements CatFavorStateHolder {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_CAT_FAVOR_TAG = "unsuspiciousblock_cat_favor";

    @Unique
    private final CatFavorState unsuspiciousblock$catFavorState = new CatFavorState();

    // 返回挂载在玩家身上的猫之恩惠状态
    @Override
    public CatFavorState unsuspiciousblock$getCatFavorState() {
        return this.unsuspiciousblock$catFavorState;
    }

    // 在玩家保存附加数据时写入猫之恩惠状态
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$saveCatFavorState(CompoundTag tag, CallbackInfo ci) {
        tag.put(UNSUSPICIOUSBLOCK_CAT_FAVOR_TAG, this.unsuspiciousblock$catFavorState.toTag());
    }

    // 在玩家读取附加数据时恢复猫之恩惠状态
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadCatFavorState(CompoundTag tag, CallbackInfo ci) {
        if (!tag.contains(UNSUSPICIOUSBLOCK_CAT_FAVOR_TAG, Tag.TAG_COMPOUND)) {
            this.unsuspiciousblock$catFavorState.clear();
            return;
        }
        this.unsuspiciousblock$catFavorState.readFrom(tag.getCompound(UNSUSPICIOUSBLOCK_CAT_FAVOR_TAG));
    }
}
