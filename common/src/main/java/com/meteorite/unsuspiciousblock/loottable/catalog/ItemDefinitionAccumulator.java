package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单条签名的物品定义累加器——把一个物品在同一张表里的多条获取路径合并成一条 {@link ItemDefinition}。
 * <p>
 * 合并规则必须只有一份实现：同一 JSON 展开出的结果要与解析、投影两条路径逐位一致。
 * 规则为"先出现者胜"——同签名的展示名与 tooltip 提示以第一次出现为准，仅当后续出现的
 * 值不同时才退回按签名重新解析（{@link LootTableCatalog#resolveMergedDisplayName} /
 * {@link LootTableCatalog#resolveMergedTooltipHint}）。
 */
public final class ItemDefinitionAccumulator {
    private final ResourceLocation id;
    private final LootResultSignature signature;
    private final List<LootAcquisitionPath> acquisitionPaths = new ArrayList<>();
    private Component displayName;
    @Nullable
    private Component tooltipHint;

    public ItemDefinitionAccumulator(ResourceLocation id, Component displayName,
                                     @Nullable Component tooltipHint, LootResultSignature signature) {
        this.id = id;
        this.displayName = displayName;
        this.tooltipHint = tooltipHint;
        this.signature = signature;
    }

    /**
     * 合并一条获取路径。展示名与 tooltip 提示按"先出现者胜"保留，出现分歧时退回按签名重解析。
     */
    public void merge(Component resolvedDisplayName, @Nullable Component resolvedTooltipHint,
                      LootAcquisitionPath acquisitionPath) {
        if (!this.acquisitionPaths.contains(acquisitionPath)) {
            this.acquisitionPaths.add(acquisitionPath);
        }
        if (!this.displayName.getString().equals(resolvedDisplayName.getString())) {
            this.displayName = LootTableCatalog.resolveMergedDisplayName(this.id, this.signature);
        }
        String currentHint = this.tooltipHint != null ? this.tooltipHint.getString() : null;
        String resolvedHint = resolvedTooltipHint != null ? resolvedTooltipHint.getString() : null;
        if (Objects.equals(currentHint, resolvedHint)) {
            return;
        }
        this.tooltipHint = LootTableCatalog.resolveMergedTooltipHint(this.signature);
    }

    public LootResultSignature signature() {
        return this.signature;
    }

    /** 构建条目；概率保持未知占位，等待模拟填充。 */
    public ItemDefinition build() {
        return new ItemDefinition(this.id, this.displayName, this.tooltipHint, Probability.unknown(),
                this.signature, this.acquisitionPaths, false);
    }
}
