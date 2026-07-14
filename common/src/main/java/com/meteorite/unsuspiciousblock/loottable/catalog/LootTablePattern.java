package com.meteorite.unsuspiciousblock.loottable.catalog;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 战利品表匹配规则——把配置字符串解析为可匹配的规则对象。
 * <p>
 * 语法约定：
 * <ul>
 *   <li>{@code <namespace>:<path>} —— 限定命名空间；裸 {@code <path>} —— 匹配所有命名空间。</li>
 *   <li>path 以 {@code /} 结尾 —— 视为路径前缀匹配（命中该前缀下所有表）。</li>
 *   <li>path 不以 {@code /} 结尾 —— 视为路径精确匹配（仅命中单个表）。</li>
 * </ul>
 * 例：{@code minecraft:archaeology/desert_well} 精确匹配单表；{@code mymod:archaeology/} 匹配 mymod
 * 命名空间下的考古表；{@code archaeology/} 匹配所有命名空间下的考古表（即旧版默认行为）。
 */
public record LootTablePattern(@Nullable String namespace, String path, boolean prefix) {

    // 解析单条配置字符串为规则；非法输入返回 null
    @Nullable
    public static LootTablePattern parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String namespace = null;
        String path = trimmed;
        int colonIndex = trimmed.indexOf(':');
        if (colonIndex >= 0) {
            namespace = trimmed.substring(0, colonIndex).toLowerCase(Locale.ROOT);
            path = trimmed.substring(colonIndex + 1);
            if (namespace.isEmpty()) {
                namespace = null;
            }
        }

        boolean prefix = path.endsWith("/");
        // 去除规则前导斜杠，保持与 ResourceLocation.getPath() 同构（path 不以 / 开头）
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return null;
        }
        return new LootTablePattern(namespace, path.toLowerCase(Locale.ROOT), prefix);
    }

    // 判断目标战利品表是否命中本规则
    public boolean matches(ResourceLocation tableId) {
        if (this.namespace != null && !this.namespace.equals(tableId.getNamespace())) {
            return false;
        }
        String targetPath = tableId.getPath();
        return this.prefix ? targetPath.startsWith(this.path) : targetPath.equals(this.path);
    }

    /**
     * 剥离命中规则后的剩余 path 片段，用于生成本地化 key 与展示名。
     * 前缀规则剥去前缀部分；精确规则保留完整 path（无前缀可剥）。
     */
    public String stripFrom(String targetPath) {
        if (this.prefix && targetPath.startsWith(this.path)) {
            return targetPath.substring(this.path.length());
        }
        return targetPath;
    }
}
