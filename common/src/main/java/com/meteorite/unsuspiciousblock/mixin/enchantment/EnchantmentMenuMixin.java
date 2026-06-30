package com.meteorite.unsuspiciousblock.mixin.enchantment;

import com.meteorite.unsuspiciousblock.enchantment.reveal.EnchantmentRevealManager;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncEnchantmentRevealListPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔台菜单服务端同步 hook。
 * 在 {@link EnchantmentMenu#slotsChanged} 返回后，服务端评估揭示条件，
 * 若满足则复用 vanilla {@code getEnchantmentList}（通过 {@link Invoker} 调用，
 * 兼容其他模组对 vanilla 算法的 mixin 改写）计算 3 个槽位的完整候选列表，
 * 并通过 S2C payload 下发客户端；条件不满足时下发清空信号。
 * <p>
 * mixin 仅作为同步入口，不承载业务逻辑；列表生成完全委托给 vanilla 方法。
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {

    @Final
    @Shadow
    private Container enchantSlots;

    @Final
    @Shadow
    public int[] costs;

    @Unique
    private Player unsuspiciousblock$owner;

    // 调用 vanilla 私有方法 getEnchantmentList，保留其他模组的 mixin 改写效果
    @Invoker("getEnchantmentList")
    abstract List<EnchantmentInstance> unsuspiciousblock$invokeGetEnchantmentList(
            RegistryAccess registry, ItemStack stack, int slot, int cost);

    // 捕获打开此菜单的玩家，供后续同步判断使用
    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
            at = @At("RETURN"))
    private void unsuspiciousblock$captureOwner(int containerId, Inventory inv, ContainerLevelAccess access, CallbackInfo ci) {
        this.unsuspiciousblock$owner = inv.player;
    }

    @Inject(method = "slotsChanged", at = @At("RETURN"))
    private void unsuspiciousblock$syncRevealList(Container container, CallbackInfo ci) {
        if (container != this.enchantSlots) {
            return;
        }
        if (!(this.unsuspiciousblock$owner instanceof ServerPlayer serverPlayer)) {
            return;
        }
        EnchantmentMenu self = (EnchantmentMenu) (Object) this;
        ItemStack stack = this.enchantSlots.getItem(0);
        boolean reveal = EnchantmentRevealManager.shouldReveal(serverPlayer, self, stack);
        if (!reveal) {
            Services.NETWORK.sendToPlayer(serverPlayer,
                    new SyncEnchantmentRevealListPayload(self.containerId, false, List.of()));
            return;
        }
        List<SyncEnchantmentRevealListPayload.Entry> entries = new ArrayList<>();
        if (!stack.isEmpty() && stack.isEnchantable()) {
            RegistryAccess registry = serverPlayer.level().registryAccess();
            for (int slot = 0; slot < 3; slot++) {
                if (this.costs[slot] <= 0) {
                    continue;
                }
                List<EnchantmentInstance> list = this.unsuspiciousblock$invokeGetEnchantmentList(
                        registry, stack, slot, this.costs[slot]);
                for (EnchantmentInstance inst : list) {
                    ResourceLocation id = inst.enchantment.unwrapKey()
                            .map(ResourceKey::location)
                            .orElse(null);
                    if (id != null) {
                        entries.add(new SyncEnchantmentRevealListPayload.Entry(slot, id, inst.level));
                    }
                }
            }
        }
        Services.NETWORK.sendToPlayer(serverPlayer,
                new SyncEnchantmentRevealListPayload(self.containerId, true, entries));
    }
}
