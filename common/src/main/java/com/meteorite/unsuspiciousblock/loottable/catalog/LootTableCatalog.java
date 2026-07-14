package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 战利品表目录数据记录——TableDefinition 与 ItemDefinition 为跨模块共享的基础类型，
 * 供 journal、network、client、command 等包统一引用。
 */
public final class LootTableCatalog {

    private LootTableCatalog() {
    }

    /** 战利品表定义 */
    public record TableDefinition(ResourceLocation id, Component displayName, String type,
                                  List<ItemDefinition> items, int simulationCount) {
    }

    /** 战利品表物品条目定义 */
    public record ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                                 String probability, LootResultSignature signature,
                                 @Nullable ResourceLocation sourceChildTable,
                                 List<LootConditionInfo> conditions) {
        // 兼容旧调用方的便利构造器：sourceChildTable 默认 null，conditions 默认空
        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature) {
            this(id, displayName, tooltipHint, probability, signature, null, List.of());
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature,
                              @Nullable ResourceLocation sourceChildTable) {
            this(id, displayName, tooltipHint, probability, signature, sourceChildTable, List.of());
        }
    }
}