package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootTableJsonParser;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 考古战利品表目录——考古笔记专用的薄包装层。
 * <p>
 * 核心解析逻辑委托给 {@link LootTableJsonParser}，本类仅负责：
 * <ul>
 *   <li>以考古表过滤规则（{@link LootTableNames#isArchaeologyLootTable}）构造解析器</li>
 *   <li>加载后触发缺失翻译 key 导出</li>
 *   <li>提供 hasConditions / buildDiscoveredDefinition 等考古笔记专用辅助方法</li>
 * </ul>
 */
public final class ArchaeologyJournalCatalog {
    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";

    private static final LootTableJsonParser PARSER = new LootTableJsonParser(
            LootTableNames::isArchaeologyLootTable,
            LootTableNames::resolveDisplayName
    );

    private ArchaeologyJournalCatalog() {
    }

    /**
     * 判断 ItemDefinition 是否标记为含条件（影响模拟结果置信度）。
     * 条件包括：有 conditions 列表、或 tooltipHint 为近似提示。
     */
    public static boolean hasConditions(ItemDefinition definition) {
        if (!definition.conditions().isEmpty()) {
            return true;
        }
        return definition.tooltipHint() != null
                && definition.tooltipHint().getString().equals(
                Component.translatable(APPROXIMATE_HINT_KEY).getString());
    }

    /**
     * 从 ResourceManager 加载考古战利品表目录。
     * 委托 {@link LootTableJsonParser} 进行通用解析，并对每个表触发缺失翻译 key 导出。
     */
    public static Map<ResourceLocation, TableDefinition> load(ResourceManager resourceManager) {
        Map<ResourceLocation, TableDefinition> tables = PARSER.load(resourceManager);
        // 触发缺失 key 导出——翻译 key 仅由 tableId 派生，与 JSON 解析成功与否无关
        for (ResourceLocation tableId : tables.keySet()) {
            LootTableNames.ensureRegistered(tableId);
            LootTableNames.resolveDisplayName(tableId);
        }
        return tables;
    }

    /**
     * 为模拟期发现的"注入条目"（GLM / LootTableEvents.MODIFY 注入，JSON 中不存在）构建 ItemDefinition。
     */
    public static ItemDefinition buildDiscoveredDefinition(LootResultSignature signature, String probability) {
        ResourceLocation itemId = signature.itemId();
        Component displayName = resolveMergedDisplayName(itemId, signature);
        Component tooltipHint = resolveMergedTooltipHint(signature);
        return new ItemDefinition(itemId, displayName, tooltipHint, probability, signature);
    }

    private static Component resolveMergedDisplayName(ResourceLocation itemId, LootResultSignature signature) {
        ItemStack previewStack = signature.createPreviewStack();
        if (previewStack.isEmpty()) {
            previewStack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
        return previewStack.isEmpty()
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry")
                : previewStack.getHoverName().copy();
    }

    @Nullable
    private static Component resolveMergedTooltipHint(LootResultSignature signature) {
        if (signature.isEnchantedVariant()) {
            return Component.translatable(ENCHANTED_HINT_KEY);
        }
        if (signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY) {
            return Component.translatable(APPROXIMATE_HINT_KEY);
        }
        return null;
    }
}