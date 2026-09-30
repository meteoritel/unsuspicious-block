package com.meteorite.unsuspiciousblock.loottable.injection;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** 平台适配器明确声明的注入规则；来源是实现者，不由物品 namespace 猜测。 */
public record DeclaredLootInjection(ResourceLocation item, String source, String mode, float chance,
                                    List<LootConditionInfo> conditions) {
    public DeclaredLootInjection { conditions = List.copyOf(conditions); }
}
