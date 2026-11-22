package com.meteorite.unsuspiciousblock.core.loottable.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootTableCompiler;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 已注册且有生效 JSON 的战利品表资源，解析结果在同一资源代次内供服务端主线程共享。 */
public final class LootTableSourceSnapshot {
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");

    private final Map<ResourceLocation, Resource> resources;
    private final Map<ResourceLocation, Source> sources = new HashMap<>();

    private LootTableSourceSnapshot(Map<ResourceLocation, Resource> resources) {
        this.resources = resources;
    }

    /** 沿用原版重载的生效资源选择，并排除注册表未接受的表。 */
    public static LootTableSourceSnapshot capture(ResourceManager resourceManager,
                                                  Collection<ResourceLocation> registeredTables) {
        Set<ResourceLocation> registeredIds = Set.copyOf(registeredTables);
        Map<ResourceLocation, Resource> resources = new LinkedHashMap<>();
        LOOT_TABLES.listMatchingResources(resourceManager).forEach((file, resource) -> {
            ResourceLocation id = LOOT_TABLES.fileToId(file);
            if (registeredIds.contains(id)) {
                resources.put(id, resource);
            }
        });
        return new LootTableSourceSnapshot(Collections.unmodifiableMap(resources));
    }

    /** 返回可供静态分析的表 ID，包含合法空表。 */
    public Set<ResourceLocation> tableIds() {
        return this.resources.keySet();
    }

    /** 返回生效 JSON。分析调用方只读访问，同一资源代次只解析一次。 */
    public JsonElement effectiveJson(ResourceLocation tableId) {
        return source(tableId).json();
    }

    /** 返回本表的具名引用，按条目顺序保留首次出现位置。 */
    public Set<ResourceLocation> directReferences(ResourceLocation tableId) {
        return source(tableId).references();
    }

    /** 工具谓词包含无需枚举具体物品的附魔与组件条件。 */
    public boolean hasMatchTool(ResourceLocation tableId) {
        return source(tableId).hasMatchTool();
    }

    /** 将生效原文与来源数据包的摘要纳入调用方的内容指纹。 */
    public void updateSourceDigest(ResourceLocation tableId, MessageDigest digest) {
        digest.update(source(tableId).digest());
    }

    private Source source(ResourceLocation tableId) {
        return this.sources.computeIfAbsent(tableId, this::readSource);
    }

    private Source readSource(ResourceLocation tableId) {
        Resource resource = this.resources.get(tableId);
        try (BufferedReader reader = resource.openAsReader()) {
            StringWriter contents = new StringWriter();
            reader.transferTo(contents);
            String text = contents.toString();
            JsonElement json = JsonParser.parseString(text);
            MessageDigest digest = newDigest();
            updateDigest(digest, resource.sourcePackId());
            updateDigest(digest, text);
            return new Source(json, LootTableCompiler.namedReferences(json),
                    LootTableCompiler.hasMatchTool(json), digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException("读取已注册战利品表失败 " + tableId, exception);
        } catch (JsonParseException exception) {
            throw new IllegalStateException("解析已注册战利品表失败 " + tableId, exception);
        }
    }

    /** 创建用于战利品表内容身份的 SHA-256 摘要。 */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /** 以 UTF-8 写入摘要，用零字节分隔字段。 */
    public static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record Source(JsonElement json, Set<ResourceLocation> references, boolean hasMatchTool, byte[] digest) {}
}
