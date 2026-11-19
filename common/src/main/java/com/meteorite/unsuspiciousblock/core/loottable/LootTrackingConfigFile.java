package com.meteorite.unsuspiciousblock.core.loottable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 战利品表世界配置的字段编解码与原子替换。 */
public final class LootTrackingConfigFile {
    static final String DIRECTORY = "serverconfig";
    static final String NAME = "unsuspiciousblock-loot-tracking.json";
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA = "schema_version";
    private static final String FOLLOW = "follow_builtin_rules";
    private static final String DISABLED = "disabled_builtin_rules";
    private static final String ADDITIONAL = "additional_tracking_rules";
    private static final String EXCLUDED = "excluded_loot_tables";
    private static final String COMPONENTS = "signature_excluded_components";
    private static final String LOG_LIMIT = "max_log_entries_per_table";
    private static final String TIMEOUT = "tracking_timeout_ticks";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private LootTrackingConfigFile() {}

    static LootTrackingSettings load(Path file) throws IOException {
        JsonObject json = readObject(file);
        if (!Integer.valueOf(SCHEMA_VERSION).equals(readInt(json, SCHEMA))) {
            throw new IllegalArgumentException(SCHEMA + ": " + json.get(SCHEMA));
        }
        return readSettings(json);
    }

    private static LootTrackingSettings readSettings(JsonObject json) {
        if (json.has(FOLLOW) && !requirePrimitive(json.get(FOLLOW), FOLLOW).isBoolean()) {
            throw new IllegalArgumentException(FOLLOW);
        }
        return new LootTrackingSettings(!json.has(FOLLOW) || json.get(FOLLOW).getAsBoolean(),
                readListOrEmpty(json, DISABLED), readListOrEmpty(json, ADDITIONAL), readListOrEmpty(json, EXCLUDED),
                readList(json, COMPONENTS), readInt(json, LOG_LIMIT), readLong(json, TIMEOUT));
    }

    static void save(Path file, LootTrackingSettings settings) throws IOException {
        JsonObject json = settingsObject(settings);
        json.addProperty(SCHEMA, SCHEMA_VERSION);
        writeObject(file, json);
    }

    /** 网络草稿只传当前可编辑的字段，磁盘版本由文件读写负责。 */
    public static String encodeSettings(LootTrackingSettings settings) {
        return settingsObject(settings).toString();
    }

    /** 校验客户端草稿，字段非法时抛出适于显示的参数错误。 */
    public static LootTrackingSettings decodeSettings(String text) {
        try {
            return readSettings(readObject(new StringReader(text)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid loot tracking settings JSON", exception);
        }
    }

    private static JsonObject settingsObject(LootTrackingSettings settings) {
        JsonObject json = new JsonObject();
        json.addProperty(FOLLOW, settings.followBuiltinRules());
        json.add(DISABLED, JSON.toJsonTree(settings.disabledBuiltinRules()));
        json.add(ADDITIONAL, JSON.toJsonTree(settings.additionalTrackingRules()));
        json.add(EXCLUDED, JSON.toJsonTree(settings.excludedLootTables()));
        if (settings.signatureExcludedComponents() != null) {
            json.add(COMPONENTS, JSON.toJsonTree(settings.signatureExcludedComponents()));
        }
        if (settings.maxLogEntriesPerTable() != null) json.addProperty(LOG_LIMIT, settings.maxLogEntriesPerTable());
        if (settings.trackingTimeoutTicks() != null) json.addProperty(TIMEOUT, settings.trackingTimeoutTicks());
        return json;
    }

    /** 在同目录完成写入后原子替换 JSON 文件。 */
    public static void writeObject(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(json) + "\n");
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 读取 JSON 对象，区分文件不存在、I/O 错误与内容错误。 */
    public static JsonObject readObject(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return readObject(reader);
        }
    }

    private static JsonObject readObject(Reader reader) throws IOException {
        JsonReader input = new JsonReader(reader);
        input.setLenient(false);
        JsonObject object = JSON.getAdapter(JsonObject.class).read(input);
        if (object == null || input.peek() != JsonToken.END_DOCUMENT) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return object;
    }

    /** Fabric 旧文件曾同时承载全局配置，按调用者选择的作用域提取旧字段。 */
    public static LegacyLootTrackingConfig readFabricLegacy(Path file) throws IOException {
        JsonObject json = readObject(file);
        for (String key : List.of("archaeology_path_prefixes", EXCLUDED, COMPONENTS, LOG_LIMIT, TIMEOUT)) {
            if (json.has(key) && json.get(key).isJsonNull()) json.remove(key);
            if (json.get(key) instanceof JsonArray array) array.asList().removeIf(JsonElement::isJsonNull);
        }
        List<String> exclusions = readListOrEmpty(json, EXCLUDED).stream().map(String::trim).toList();
        List<String> components = readList(json, COMPONENTS);
        return new LegacyLootTrackingConfig(readList(json, "archaeology_path_prefixes"), exclusions,
                components == null ? null : components.stream().map(String::trim).toList(),
                readInt(json, LOG_LIMIT), readLong(json, TIMEOUT));
    }

    @Nullable
    private static List<String> readList(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null) return null;
        if (!(value instanceof JsonArray array)) throw new IllegalArgumentException(key);
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            JsonPrimitive text = requirePrimitive(element, key);
            if (!text.isString()) throw new IllegalArgumentException(key);
            values.add(text.getAsString());
        }
        return values;
    }

    private static List<String> readListOrEmpty(JsonObject json, String key) {
        List<String> list = readList(json, key);
        return list == null ? List.of() : list;
    }

    @Nullable
    private static Integer readInt(JsonObject json, String key) {
        Long value = readLong(json, key);
        if (value == null) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key);
        return value.intValue();
    }

    @Nullable
    private static Long readLong(JsonObject json, String key) {
        if (!json.has(key)) return null;
        JsonPrimitive value = requirePrimitive(json.get(key), key);
        if (!value.isNumber()) throw new IllegalArgumentException(key);
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(key, exception);
        }
    }

    private static JsonPrimitive requirePrimitive(JsonElement element, String key) {
        if (!(element instanceof JsonPrimitive primitive)) throw new IllegalArgumentException(key);
        return primitive;
    }
}
