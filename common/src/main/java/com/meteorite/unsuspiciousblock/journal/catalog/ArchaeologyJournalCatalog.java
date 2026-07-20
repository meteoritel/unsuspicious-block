package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootTableJsonParser;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;

/**
 * 考古战利品表目录——考古笔记专用的薄包装层。
 * <p>
 * 核心解析逻辑委托给 {@link LootTableJsonParser}，本类仅负责：
 * <ul>
 *   <li>以考古表过滤规则（{@link LootTableNames#isArchaeologyLootTable}）构造解析器</li>
 *   <li>加载后触发缺失翻译 key 导出</li>
 * </ul>
 */
public final class ArchaeologyJournalCatalog {

    private static final LootTableJsonParser PARSER = new LootTableJsonParser(
            LootTableNames::isArchaeologyLootTable,
            LootTableNames::resolveDisplayName
    );

    private ArchaeologyJournalCatalog() {
    }

    /**
     * 从 ResourceManager 加载考古战利品表目录。
     * 委托 {@link LootTableJsonParser} 进行通用解析，并对每个表触发缺失翻译 key 导出。
     */
    public static Map<ResourceLocation, TableDefinition> load(ResourceManager resourceManager,
                                                               HolderLookup.Provider registries) {
        Map<ResourceLocation, TableDefinition> tables = PARSER.load(resourceManager, registries);
        // 触发缺失 key 导出——翻译 key 仅由 tableId 派生，与 JSON 解析成功与否无关
        for (ResourceLocation tableId : tables.keySet()) {
            LootTableNames.ensureRegistered(tableId);
            LootTableNames.resolveDisplayName(tableId);
        }
        return tables;
    }
}
