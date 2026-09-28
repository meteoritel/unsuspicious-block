package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.entry.ItemEntryLike;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Locale;

/**
 * 考古笔记搜索查询解析器。
 * <p>
 * 支持的前缀：
 * <ul>
 *   <li>无前缀 — 按战利品表名称搜索（默认）</li>
 *   <li>{@code @} — 按命名空间/模组来源搜索战利品表</li>
 *   <li>{@code %} — 按战利品表类型搜索（如 {@code %archaeology} 匹配 {@code minecraft:archaeology}）</li>
 *   <li>{@code $} — 按物品名称搜索（{@code $#} 开头则按物品标签搜索）</li>
 * </ul>
 */
public final class JournalSearchQuery {

    public enum Mode {
        TABLE_NAME(""),      // 无前缀：按名称搜索战利品表
        NAMESPACE("@"),      // @ 前缀：按命名空间搜索战利品表
        TYPE("%"),           // % 前缀：按战利品表类型搜索
        ITEM_NAME("$");      // $ 前缀：按物品搜索（# 开头则为标签搜索）

        private final String prefix;

        Mode(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return this.prefix;
        }
    }

    private final Mode mode;
    private final String rawQuery;
    private final String normalizedQuery;

    private JournalSearchQuery(Mode mode, String rawQuery, String normalizedQuery) {
        this.mode = mode;
        this.rawQuery = rawQuery;
        this.normalizedQuery = normalizedQuery;
    }

    // 空查询——匹配全部
    public static final JournalSearchQuery EMPTY = new JournalSearchQuery(Mode.TABLE_NAME, "", "");

    // 从输入文本解析搜索查询
    public static JournalSearchQuery parse(String input) {
        if (input == null || input.isBlank()) {
            return EMPTY;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return EMPTY;
        }

        // 尝试匹配单字符前缀
        Mode mode = Mode.TABLE_NAME;
        char first = trimmed.charAt(0);
        for (Mode m : Mode.values()) {
            if (!m.prefix().isEmpty() && m.prefix().charAt(0) == first) {
                mode = m;
                trimmed = trimmed.substring(1).trim();
                break;
            }
        }

        // 仅前缀无内容时仍匹配全部，但保留原始输入供搜索框显示与状态恢复。
        if (trimmed.isEmpty()) {
            return new JournalSearchQuery(mode, input, "");
        }
        return new JournalSearchQuery(mode, input, trimmed.toLowerCase(Locale.ROOT));
    }

    // 使用完整物品注册名构造物品搜索，供 JEI/背包悬停快捷查询复用。
    public static JournalSearchQuery forItemId(ResourceLocation itemId) {
        return parse(Mode.ITEM_NAME.prefix() + itemId);
    }

    public Mode mode() {
        return this.mode;
    }

    public String rawQuery() {
        return this.rawQuery;
    }

    public boolean isEmpty() {
        return this.normalizedQuery.isEmpty();
    }

    // 锁定条目只允许通过完整战利品表 ID 精确命中，避免搜索泄露名称或物品。
    public boolean matchesLockedTableId(ResourceLocation id) {
        return this.mode == Mode.TABLE_NAME && id.toString().equals(this.normalizedQuery);
    }

    // 判断目录条目（战利品表）是否匹配此查询
    public boolean matchesCatalogEntry(ResourceLocation id, String displayName, String type) {
        return matchesTableLowercase(id, displayName.toLowerCase(Locale.ROOT), type);
    }

    // 表级匹配（显示名已由调用方小写化）：与 matchesCatalogEntry 同一口径，只省掉重复的 toLowerCase
    public boolean matchesTableLowercase(ResourceLocation id, String lowercaseDisplayName, String type) {
        if (this.isEmpty()) return true;
        return switch (this.mode) {
            case TABLE_NAME -> lowercaseDisplayName.contains(this.normalizedQuery)
                    || id.getPath().toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            case NAMESPACE -> id.getNamespace().toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            case TYPE -> type.toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            // 物品级搜索模式需要在条目级遍历物品，目录级先全部保留
            case ITEM_NAME -> true;
        };
    }

    // 判断物品条目是否匹配此查询（用于高亮等）
    public boolean matchesItem(ResourceLocation id, String displayName) {
        return matchesItemLowercase(LowercaseItem.of(id, displayName));
    }

    // 物品级匹配：名称与 id 文本已小写化（调用方可按目录 revision 预建，避免逐次 getString()+toLowerCase）
    public boolean matchesItemLowercase(LowercaseItem item) {
        if (this.isEmpty()) return true;
        return switch (this.mode) {
            case TABLE_NAME -> item.lowercaseDisplayName().contains(this.normalizedQuery)
                    || item.lowercasePath().contains(this.normalizedQuery);
            case NAMESPACE -> item.lowercaseNamespace().contains(this.normalizedQuery);
            case TYPE -> true;
            case ITEM_NAME -> {
                // # 开头 → 标签搜索
                if (this.normalizedQuery.startsWith("#")) {
                    yield matchesByTag(item.id(), this.normalizedQuery.substring(1));
                }
                yield item.lowercaseId().equals(this.normalizedQuery)
                        || item.lowercaseDisplayName().contains(this.normalizedQuery)
                        || item.lowercasePath().contains(this.normalizedQuery);
            }
        };
    }

    // 判断整个战利品表是否因含有匹配物品而匹配此查询
    public boolean matchesTableByItem(ResourceLocation tableId, String tableDisplayName,
                                      String type, Iterable<? extends ItemEntryLike> items) {
        if (this.isEmpty()) return true;
        return switch (this.mode) {
            case TABLE_NAME, NAMESPACE, TYPE -> matchesCatalogEntry(tableId, tableDisplayName, type);
            case ITEM_NAME -> {
                for (ItemEntryLike item : items) {
                    if (matchesItem(item.itemId(), item.itemDisplayName())) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    // 表级物品搜索：表名与物品文本均已小写化（物品文本可由宿主按目录 revision 预建）
    public boolean matchesTableByItemLowercase(ResourceLocation tableId, String lowercaseDisplayName, String type,
                                               Iterable<LowercaseItem> items) {
        if (this.isEmpty()) return true;
        return switch (this.mode) {
            case TABLE_NAME, NAMESPACE, TYPE -> matchesTableLowercase(tableId, lowercaseDisplayName, type);
            case ITEM_NAME -> {
                for (LowercaseItem item : items) {
                    if (matchesItemLowercase(item)) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    /**
     * 物品的小写匹配文本（id 全名、命名空间、路径与显示名各一份）。
     * <p>
     * 由调用方按目录 revision 预建：物品显示名的 {@code getString()} 是一次翻译查询，
     * 每次匹配都重做一遍会随目录规模放大；缓存失效点由调用方声明。
     */
    public record LowercaseItem(ResourceLocation id, String lowercaseId, String lowercaseNamespace,
                                String lowercasePath, String lowercaseDisplayName) {

        // 从物品 id 与显示名生成小写匹配文本
        public static LowercaseItem of(ResourceLocation id, String displayName) {
            return new LowercaseItem(id,
                    id.toString().toLowerCase(Locale.ROOT),
                    id.getNamespace().toLowerCase(Locale.ROOT),
                    id.getPath().toLowerCase(Locale.ROOT),
                    displayName.toLowerCase(Locale.ROOT));
        }
    }

    // 通过标签匹配物品（tagQuery 不包含 # 前缀）
    private boolean matchesByTag(ResourceLocation itemId, String tagQuery) {
        if (tagQuery.isEmpty()) return true;
        Holder<Item> holder = BuiltInRegistries.ITEM.getHolder(itemId).orElse(null);
        if (holder == null) return false;

        // 尝试将查询解析为完整 ResourceLocation（ns:path）
        if (tagQuery.contains(":")) {
            try {
                ResourceLocation tagId = ResourceLocation.parse(tagQuery);
                TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagId);
                return holder.is(tagKey);
            } catch (Exception e) {
                // 解析失败，回退到部分匹配
            }
        }

        // 部分匹配：遍历已注册标签，匹配路径包含查询的标签
        for (TagKey<Item> tag : holder.tags().toList()) {
            String tagPath = tag.location().getPath().toLowerCase(Locale.ROOT);
            String tagFull = tag.location().toString().toLowerCase(Locale.ROOT);
            if (tagPath.contains(tagQuery) || tagFull.contains(tagQuery)) {
                return true;
            }
        }
        return false;
    }

}
