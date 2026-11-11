package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.jetbrains.annotations.Nullable;

/** 可选静态预览能力；调用方提供隔离副本，失败结果不发布该副本。 */
@FunctionalInterface
public interface LootFunctionPreview {
    FunctionPreviewResult apply(ItemStack previewStack, LootItemFunction function, LootFunctionInfo description);

    /** 返回签名覆盖；无覆盖时由最终预览栈决定。 */
    default @Nullable LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
        return null;
    }
}
