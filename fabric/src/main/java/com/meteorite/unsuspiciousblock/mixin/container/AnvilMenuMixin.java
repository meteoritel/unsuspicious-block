package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/***
 * Fabric 平台古代金币铁砧修复入口。
 * <p>
 * 在 {@link AnvilMenu#createResult()} 所有提前 return 之后再注入：仅当原版（或其他 mod）
 * 未产生结果、左侧为带耐久物品且右侧为古代金币时，覆盖输出并设置消耗与经验成本。
 * 每枚金币修复目标物品 25% 最大耐久（与原版同类材料修复一致），并同步原版重命名逻辑。
 * <p>
 * 兼容性策略：
 * <ul>
 *   <li>注入点 {@code @At("RETURN")} 覆盖全部 return 路径，不依赖具体 INVOKE 字节码</li>
 *   <li>仅在结果槽为空时介入，避免覆盖其他 mod 的输出</li>
 *   <li>不修改原版分支与局部变量，对其他 mixin 完全透明</li>
 * </ul>
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

    // 原版 AnvilMenu.INPUT_SLOT / ADDITIONAL_SLOT / RESULT_SLOT
    @Unique
    private static final int INPUT_SLOT = 0;
    @Unique
    private static final int ADDITIONAL_SLOT = 1;
    @Unique
    private static final int RESULT_SLOT = 2;
    // 每枚古代金币修复的最大耐久比例：1/4（与原版同类材料修复一致）
    @Unique
    private static final int ANCIENT_COIN_REPAIR_DIVISOR = 4;

    @Shadow
    private int repairItemCountCost;

    @Final
    @Shadow
    private DataSlot cost;

    @Shadow
    @Nullable
    private String itemName;

    // 在原方法所有 return 之后介入：若结果为空且右侧为古代金币，则生成修复输出并同步重命名
    @Inject(method = "createResult", at = @At("RETURN"))
    private void unsuspiciousblock$ancientCoinRepair(CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        ItemStack current = menu.getSlot(RESULT_SLOT).getItem();
        // 已被原版或其他 mod 处理则不覆盖
        if (!current.isEmpty()) {
            return;
        }
        ItemStack left = menu.getSlot(INPUT_SLOT).getItem();
        ItemStack right = menu.getSlot(ADDITIONAL_SLOT).getItem();
        if (!right.is(ModItems.ANCIENT_COIN)) {
            return;
        }
        if (!left.isDamageableItem() || left.getDamageValue() <= 0) {
            return;
        }

        ItemStack result = left.copy();
        // 每枚金币修复 1/4 最大耐久；maxDamage 至少为 1 以免除零
        int repairPerCoin = Math.max(1, result.getMaxDamage() / ANCIENT_COIN_REPAIR_DIVISOR);
        int remaining = result.getDamageValue();
        int coinsUsed = 0;
        while (remaining > 0 && coinsUsed < right.getCount()) {
            remaining -= repairPerCoin;
            coinsUsed++;
        }
        result.setDamageValue(Math.max(0, remaining));

        // 同步重命名：原版在右侧非有效修复材料时提前 return，命名处理未触发，
        // 故此处复用原版命名规则——非空且不同的名称设为 CUSTOM_NAME，空名称清除现有 CUSTOM_NAME
        int renameCost = 0;
        if (this.itemName != null && !StringUtil.isBlank(this.itemName)) {
            if (!this.itemName.equals(left.getHoverName().getString())) {
                result.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
                renameCost = 1;
            }
        } else if (this.itemName != null && left.has(DataComponents.CUSTOM_NAME)) {
            // 用户清空名称栏 → 移除自定义名
            result.remove(DataComponents.CUSTOM_NAME);
            renameCost = 1;
        }

        // 等价原版同类材料修复：base = 左右修复成本之和，每枚金币 +1，重命名 +1
        int baseCost = left.getOrDefault(DataComponents.REPAIR_COST, 0)
                + right.getOrDefault(DataComponents.REPAIR_COST, 0);
        this.cost.set(baseCost + coinsUsed + renameCost);
        // onTake 依据此值消耗右侧金币；不设置会清空整堆
        this.repairItemCountCost = coinsUsed;
        menu.getSlot(RESULT_SLOT).set(result);
    }
}
