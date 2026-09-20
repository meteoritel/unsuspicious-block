package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条签名的物品定义累加器——把一个物品在同一张表里的多条获取路径合并成一条 {@link ItemDefinition}。
 * <p>
 * 合并规则必须只有一份实现：同一 JSON 展开出的结果要与解析、投影两条路径逐位一致。
 * 展示名出现分歧时按签名重新解析；非空提示按组件结构去重、按路径出现顺序保留，
 * 用本地化分隔符表达不同路径的备选效果，不依赖服务端是否加载语言表。
 */
public final class ItemDefinitionAccumulator {
    private final ResourceLocation id;
    private final LootResultSignature signature;
    private final List<LootAcquisitionPath> acquisitionPaths = new ArrayList<>();
    private Component displayName;
    private final List<Component> tooltipHints = new ArrayList<>();

    public ItemDefinitionAccumulator(ResourceLocation id, Component displayName,
                                     @Nullable Component tooltipHint, LootResultSignature signature) {
        this.id = id;
        this.displayName = displayName;
        addTooltipHint(tooltipHint);
        this.signature = signature;
    }

    // 合并一条获取路径；提示不同不代表失效，必须保留各路径的已知信息。
    public void merge(Component resolvedDisplayName, @Nullable Component resolvedTooltipHint,
                      LootAcquisitionPath acquisitionPath) {
        if (!this.acquisitionPaths.contains(acquisitionPath)) {
            this.acquisitionPaths.add(acquisitionPath);
        }
        if (!this.displayName.equals(resolvedDisplayName)) {
            this.displayName = LootTableCatalog.resolveMergedDisplayName(this.id, this.signature);
        }
        addTooltipHint(resolvedTooltipHint);
    }

    // 空提示没有额外信息；复制组件，避免调用方后续修改影响去重与最终目录。
    private void addTooltipHint(@Nullable Component hint) {
        if (hint != null && !this.tooltipHints.contains(hint)) {
            this.tooltipHints.add(hint.copy());
        }
    }

    // 保留每条提示整体，避免把多个函数效果误解释为同一路径上的叠加结果。
    @Nullable
    private Component mergedTooltipHint() {
        if (this.tooltipHints.isEmpty()) {
            return null;
        }
        var result = this.tooltipHints.getFirst().copy();
        for (int i = 1; i < this.tooltipHints.size(); i++) {
            result.append(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.alternative_separator"));
            result.append(this.tooltipHints.get(i).copy());
        }
        return result;
    }

    public LootResultSignature signature() {
        return this.signature;
    }

    // 构建条目；概率保持未知占位，等待模拟填充。
    public ItemDefinition build() {
        return new ItemDefinition(this.id, this.displayName, mergedTooltipHint(),
                Probability.unknown(UnknownReason.UNCOVERED),
                this.signature, this.acquisitionPaths, false);
    }
}
