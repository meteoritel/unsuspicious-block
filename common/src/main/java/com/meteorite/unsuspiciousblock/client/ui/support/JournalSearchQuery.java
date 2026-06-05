package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.loottable.ProbabilityFormat;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * 考古手册搜索查询解析器。
 * <p>
 * 支持的前缀：
 * <ul>
 *   <li>{@code @} — 按名称搜索（默认）</li>
 *   <li>{@code #} — 按命名空间/模组来源搜索</li>
 *   <li>{@code $} — 按解锁状态搜索（locked / unlocked）</li>
 *   <li>{@code %} — 按稀有度/概率阈值搜索</li>
 * </ul>
 * 无前缀时默认按名称搜索。
 */
public final class JournalSearchQuery {

    public enum Mode {
        NAME("@"),
        NAMESPACE("#"),
        UNLOCK("$"),
        RARITY("%");

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
    public static final JournalSearchQuery EMPTY = new JournalSearchQuery(Mode.NAME, "", "");

    // 从输入文本解析搜索查询
    public static JournalSearchQuery parse(String input) {
        if (input == null || input.isBlank()) {
            return EMPTY;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return EMPTY;
        }

        Mode mode = Mode.NAME;
        // 检查前缀
        if (trimmed.length() > 1) {
            char first = trimmed.charAt(0);
            for (Mode m : Mode.values()) {
                if (m.prefix().charAt(0) == first) {
                    mode = m;
                    trimmed = trimmed.substring(1).trim();
                    break;
                }
            }
        }

        if (trimmed.isEmpty()) {
            return EMPTY;
        }
        return new JournalSearchQuery(mode, input, trimmed.toLowerCase(Locale.ROOT));
    }

    public Mode mode() {
        return this.mode;
    }

    public String rawQuery() {
        return this.rawQuery;
    }

    public String normalizedQuery() {
        return this.normalizedQuery;
    }

    public boolean isEmpty() {
        return this.normalizedQuery.isEmpty();
    }

    // 判断目录条目是否匹配此查询
    public boolean matchesCatalogEntry(ResourceLocation id, String displayName, boolean unlocked) {
        if (this.isEmpty()) return true;
        return switch (this.mode) {
            case NAME -> displayName.toLowerCase(Locale.ROOT).contains(this.normalizedQuery)
                    || id.getPath().toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            case NAMESPACE -> id.getNamespace().toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            case UNLOCK -> {
                String q = this.normalizedQuery;
                if (q.startsWith("unlock")) yield unlocked;
                if (q.startsWith("lock")) yield !unlocked;
                yield displayName.toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            }
            case RARITY -> true; // 稀有度过滤在条目级处理，目录级不做过滤
        };
    }

    // 判断物品条目是否匹配此查询
    public boolean matchesItem(ResourceLocation id, String displayName, boolean unlocked, String probability) {
        if (this.isEmpty()) return true;
        return switch (this.mode) {
            case NAME -> displayName.toLowerCase(Locale.ROOT).contains(this.normalizedQuery)
                    || id.getPath().toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            case NAMESPACE -> id.getNamespace().toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            case UNLOCK -> {
                String q = this.normalizedQuery;
                if (q.startsWith("unlock")) yield unlocked;
                if (q.startsWith("lock")) yield !unlocked;
                yield displayName.toLowerCase(Locale.ROOT).contains(this.normalizedQuery);
            }
            case RARITY -> {
                // 解析概率阈值，例如 %5 匹配概率≤5%的物品
                double fraction = ProbabilityFormat.parsePercentToFraction(probability);
                if (fraction < 0) yield true; // 无法解析的概率（如 "?"），不做过滤
                try {
                    double threshold = Double.parseDouble(this.normalizedQuery);
                    yield fraction * 100.0 <= threshold;
                } catch (NumberFormatException e) {
                    yield true;
                }
            }
        };
    }

    // 排序方式
    public enum SortOrder {
        DEFAULT("default"),
        NAME("name"),
        RARITY("rarity"),
        UNLOCK("unlock");

        private final String key;

        SortOrder(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }
    }
}