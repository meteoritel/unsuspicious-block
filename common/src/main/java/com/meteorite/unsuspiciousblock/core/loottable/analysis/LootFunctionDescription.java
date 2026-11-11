package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import com.google.gson.JsonObject;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

/** 静态规则描述能力，预览结果不影响规则树。 */
@FunctionalInterface
public interface LootFunctionDescription {
    LootFunctionInfo describe(LootItemFunction function, JsonObject source);
}
