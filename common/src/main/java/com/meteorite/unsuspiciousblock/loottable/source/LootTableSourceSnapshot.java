package com.meteorite.unsuspiciousblock.loottable.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootParseUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次数据包重载的战利品表资源快照——把"读盘"从解析、建图与哈希三处收敛为一次捕获。
 * <p>
 * 每个表 id 对应一条<b>完整资源栈</b>（由高到低的数据包层）。其中：
 * <ul>
 *   <li>栈首（index 0）是该表的<b>有效原文</b>，语义等价于今天的 {@code listMatchingResources} /
 *        {@code ResourceManager#getResource}，供解析与建图使用；</li>
 *   <li>整个栈用于哈希，语义等价于今天的 {@code getResourceStack}。</li>
 * </ul>
 * 原文与 {@link JsonElement} 均在首次访问时读取并缓存：{@code JsonElement} 不常驻，
 * 需要时按缓存的原文重新解析。
 * <p>
 * 线程安全：捕获与访问都发生在服务端主线程的目录构建期间。惰性缓存使用
 * {@link ConcurrentHashMap}，避免工作器 tick 期间的偶发并发访问破坏缓存。
 */
public final class LootTableSourceSnapshot {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final String LOOT_TABLE_TYPE = "loot_table";

    /** 表 id -> 完整资源栈（index 0 为最高优先级层，即有效原文所在层）。 */
    private final Map<ResourceLocation, List<Resource>> stacks;
    /** 表 id -> 有效原文（惰性缓存，读失败时记录为缺省，不缓存异常）。 */
    private final Map<ResourceLocation, String> effectiveTexts = new ConcurrentHashMap<>();
    /** 表 id -> 有效原文中的直接 loot_table 引用（惰性缓存，保持 JSON 出现顺序）。 */
    private final Map<ResourceLocation, Set<ResourceLocation>> directReferences = new ConcurrentHashMap<>();

    private LootTableSourceSnapshot(Map<ResourceLocation, List<Resource>> stacks) {
        this.stacks = stacks;
    }

    /**
     * 一次遍历捕获全部战利品表资源栈。
     * 使用 {@code listMatchingResourceStacks} 而非 {@code listMatchingResources} + {@code getResourceStack}，
     * 使有效原文与哈希输入来自同一次列举，最高优先级层不被重复打开。
     */
    public static LootTableSourceSnapshot capture(ResourceManager resourceManager) {
        Map<ResourceLocation, List<Resource>> stacks = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<Resource>> entry
                : LOOT_TABLES.listMatchingResourceStacks(resourceManager).entrySet()) {
            List<Resource> stack = entry.getValue();
            if (stack.isEmpty()) {
                continue;
            }
            stacks.put(LOOT_TABLES.fileToId(entry.getKey()), List.copyOf(stack));
        }
        return new LootTableSourceSnapshot(Collections.unmodifiableMap(stacks));
    }

    /** 快照中的全部表 id（含没有任何引用的叶子表）。 */
    public Set<ResourceLocation> tableIds() {
        return this.stacks.keySet();
    }

    /** 快照中的表数量。 */
    public int size() {
        return this.stacks.size();
    }

    /** 该表在本次重载中是否存在资源。 */
    public boolean hasResource(ResourceLocation tableId) {
        return this.stacks.containsKey(tableId);
    }

    /** 该表的完整资源栈（由高到低的层序）；表不存在时返回空列表。 */
    public List<Resource> resourceStack(ResourceLocation tableId) {
        return this.stacks.getOrDefault(tableId, List.of());
    }

    /**
     * 该表的有效原文（最高优先级层的全文）；表缺失或读取失败时返回 {@code null}。
     */
    @Nullable
    public String effectiveText(ResourceLocation tableId) {
        String cached = this.effectiveTexts.get(tableId);
        if (cached != null) {
            return cached;
        }
        List<Resource> stack = this.stacks.get(tableId);
        if (stack == null) {
            return null;
        }
        try {
            String text = readText(stack.getFirst());
            this.effectiveTexts.put(tableId, text);
            return text;
        } catch (IOException exception) {
            LOGGER.warn("读取战利品表有效资源失败 {}", tableId, exception);
            return null;
        }
    }

    /** 该表有效原文解析出的 JSON；表缺失或读取失败时返回 {@code null}。 */
    @Nullable
    public JsonElement effectiveJson(ResourceLocation tableId) {
        String text = effectiveText(tableId);
        if (text == null) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException exception) {
            LOGGER.warn("解析战利品表有效资源 JSON 失败 {}", tableId, exception);
            return null;
        }
    }

    /**
     * 该表有效原文中的直接 loot_table 引用，保持 JSON 出现顺序并去重。
     * <p>
     * 类型判定统一走 {@link LootParseUtil#normalizeType}，与解析器一致；
     * 命中引用条目后不再递归其内部（与解析器的展开语义一致）。
     */
    public Set<ResourceLocation> directReferences(ResourceLocation tableId) {
        Set<ResourceLocation> cached = this.directReferences.get(tableId);
        if (cached != null) {
            return cached;
        }
        JsonElement element = effectiveJson(tableId);
        LinkedHashSet<ResourceLocation> references = new LinkedHashSet<>();
        if (element != null) {
            collectDirectReferences(element, references);
        }
        Set<ResourceLocation> result = Collections.unmodifiableSet(references);
        this.directReferences.put(tableId, result);
        return result;
    }

    /**
     * 把该表的完整资源栈摘要写入 digest：先写表 id，再按由高到低的层序写各层原文。
     * 层原文读取失败时向上抛出，由调用方按"哈希不可用"处理（触发重新模拟），
     * 不使用空摘要掩盖错误。
     */
    public void updateResourceStackDigest(ResourceLocation tableId, MessageDigest digest) throws IOException {
        updateDigest(digest, tableId.toString());
        for (Resource resource : resourceStack(tableId)) {
            updateDigest(digest, readText(resource));
        }
    }

    // 递归收集 JSON 元素中的直接战利品表引用
    private static void collectDirectReferences(JsonElement element, Set<ResourceLocation> output) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectDirectReferences(child, output);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement typeElement = object.get("type");
        if (typeElement != null && typeElement.isJsonPrimitive()
                && typeElement.getAsJsonPrimitive().isString()
                && LOOT_TABLE_TYPE.equals(LootParseUtil.normalizeType(typeElement.getAsString()))) {
            JsonElement idElement = object.has("value") ? object.get("value") : object.get("name");
            if (idElement != null && idElement.isJsonPrimitive()
                    && idElement.getAsJsonPrimitive().isString()) {
                ResourceLocation id = ResourceLocation.tryParse(idElement.getAsString());
                if (id != null) {
                    output.add(id);
                }
            }
            return;
        }
        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            collectDirectReferences(child.getValue(), output);
        }
    }

    // 逐行读取并补回换行符：与既有哈希实现保持逐字节一致，避免引入非预期的摘要漂移
    private static String readText(Resource resource) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = resource.openAsReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    /** 以统一的字节框架写入 digest（UTF-8 值 + 0 分隔），供引用图拼接编译产物摘要复用。 */
    public static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
