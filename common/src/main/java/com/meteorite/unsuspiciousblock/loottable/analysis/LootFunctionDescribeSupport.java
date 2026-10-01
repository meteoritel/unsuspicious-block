package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战利品函数静态描述的公共辅助——把"文本取自哪个 key、参数怎么表述、元数据怎么写"三件事收敛到一处。
 * <p>
 * 只服务 {@link LootFunctionHandlers} 的 {@code describe} 通道：数值提供器、附魔集合、枚举参数
 * 与 JSON 读取的口径在此统一，避免 40 个处理器各写一套近似写法，也避免出现第二套参数拼接逻辑。
 * <p>
 * 本类不反射服务器注册表，也不解析函数条件——函数自身的条件需要 DynamicOps 与注册表视图，
 * 由投影侧在拿到 {@code RegistryOps} 之后补齐；这里只描述"规则声明了什么"。
 * <p>
 * 元数据值一律是**语言无关、有界**的机器可读文本（用于去重与网络传输），不参与本地化；
 * 展示文本一律来自本地化 key，key 命名遵守规划 T1 登记的三段格式。
 */
final class LootFunctionDescribeSupport {

    static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";
    // 列表型参数只保留前若干项，避免超长集合撑大描述文本与网络载荷
    private static final int MAX_LIST_ITEMS = 8;
    // 元数据与摘要文本的硬上限，保证去重键与传输内容有界
    private static final int MAX_META_LENGTH = 200;
    // 包装函数递归展开的最大深度，防止异常 JSON 造成无限递归
    private static final int MAX_NESTED_DEPTH = 8;

    private static final Set<String> SOURCE_NAMES = Set.of(
            "this", "attacking_entity", "last_damage_player", "direct_killer",
            "killer", "killer_player", "block_entity", "entity", "storage");
    private static final Set<String> OPERATION_NAMES = Set.of(
            "add_value", "add_multiplied_base", "add_multiplied_total");
    private static final Set<String> SLOT_NAMES = Set.of(
            "any", "mainhand", "offhand", "hand", "feet", "legs", "chest", "head", "armor", "body");
    private static final Set<String> TARGET_NAMES = Set.of("custom_name", "item_name");

    private static final ThreadLocal<Integer> NESTED_DEPTH = ThreadLocal.withInitial(() -> 0);

    private LootFunctionDescribeSupport() {
    }

    // ==================== 描述 key 与文本 ====================

    // 函数主描述：function.<name>
    static Component fn(String name, Object... args) {
        return Component.translatable(KEY_PREFIX + "function." + name, args);
    }

    // 函数变体描述：function.<name>.<variant>
    static Component fnVariant(String name, String variant, Object... args) {
        return Component.translatable(KEY_PREFIX + "function." + name + "." + variant, args);
    }

    // 复用既有参数提示文案：item_hint.<name>
    static Component hint(String name, Object... args) {
        return Component.translatable(KEY_PREFIX + "item_hint." + name, args);
    }

    // 参数片段：function.param.<name>
    static Component param(String name, Object... args) {
        return Component.translatable(KEY_PREFIX + "function.param." + name, args);
    }

    // 附魔名称沿用原版 key，与既有实现口径一致
    static Component enchantName(@Nullable ResourceLocation id) {
        return id == null
                ? param("unknown")
                : Component.translatable("enchantment." + id.getNamespace() + "." + id.getPath());
    }

    // 注册名以原样文本展示：语言无关且不会出现缺失 key 的裸文本
    static Component literalOf(@Nullable ResourceLocation id) {
        return Component.literal(id != null ? id.toString() : "?");
    }

    // 布尔参数统一走 param 文案，避免把 true/false 写进句子
    static Component yesNo(boolean value) {
        return param(value ? "yes" : "no");
    }

    // 组件拼接：把补充片段追加到已本地化的主描述之后
    static Component append(Component base, Component extra) {
        return Component.literal("").append(base).append(extra);
    }

    // 组件列表拼接：分隔符由调用方给出，分隔符本身不参与本地化
    static Component join(Component separator, List<Component> parts) {
        MutableComponent result = Component.literal("");
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) {
                result.append(separator);
            }
            result.append(parts.get(index));
        }
        return result;
    }

    // 元数据与摘要一律截断，保证有界
    static String clip(@Nullable String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_META_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_META_LENGTH) + "…";
    }

    // ==================== 枚举参数 ====================

    static Component sourceParam(String name) {
        return enumParam("source", name, SOURCE_NAMES);
    }

    static Component operationParam(String name) {
        return enumParam("operation", name, OPERATION_NAMES);
    }

    static Component slotParam(String name) {
        return enumParam("slot", name, SLOT_NAMES);
    }

    static Component targetParam(String name) {
        return enumParam("target", name, TARGET_NAMES);
    }

    // 已知枚举值走本地化 key，未知值原样展示，避免出现缺失 key 的裸文本
    private static Component enumParam(String category, String name, Set<String> known) {
        return known.contains(name) ? param(category + "." + name) : Component.literal(clip(name));
    }

    // 列表操作模式名：字符串直接用，对象取 type 路径，缺失时按调用方给的默认值
    static String modeName(@Nullable JsonElement element, String fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        String type = typeName(objectOf(element));
        return type.isEmpty() ? fallback : type;
    }

    // 槽位字段的机器可读名：单值或列表统一拼成有界文本
    static String slotNames(@Nullable JsonElement element) {
        List<String> names = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    names.add(item.getAsString());
                }
            }
        } else if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            names.add(element.getAsString());
        }
        return joinIds(names);
    }

    // 槽位字段的展示文本：逐个走本地化 key 后用分隔符拼接
    static Component slotList(@Nullable JsonElement element) {
        List<Component> parts = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    parts.add(slotParam(item.getAsString()));
                }
            }
        } else if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            parts.add(slotParam(element.getAsString()));
        }
        if (parts.isEmpty()) {
            return param("any");
        }
        return join(Component.literal("/"), parts);
    }

    /** 可选附魔集合的文本与元数据；"字段缺省"与"显式空集合"必须区分。 */
    record Options(Component text, String metadata) {
    }

    // 可选附魔集合：缺省 / 显式空 / 标签引用 / 具体枚举各有独立表述
    static Options options(@Nullable JsonObject source, String key) {
        if (!has(source, key)) {
            return new Options(param("absent"), "absent");
        }
        JsonElement element = field(source, key);
        if (element == null || element.isJsonNull()) {
            return new Options(param("absent"), "absent");
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String raw = clip(element.getAsString());
            return new Options(Component.literal(raw), raw);
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) {
                return new Options(param("empty"), "empty");
            }
            List<String> ids = new ArrayList<>();
            for (JsonElement item : array) {
                if (item.isJsonPrimitive()) {
                    ids.add(item.getAsString());
                }
            }
            String joined = joinIds(ids);
            return new Options(Component.literal(joined), joined);
        }
        return new Options(param("dynamic"), "dynamic");
    }

    // ==================== JSON 读取 ====================

    @Nullable
    static JsonElement field(@Nullable JsonObject source, String key) {
        return source != null && source.has(key) ? source.get(key) : null;
    }

    // 字段存在且不是 JSON null；用于区分"缺省"与"显式空值"
    static boolean has(@Nullable JsonObject source, String key) {
        return source != null && source.has(key) && !source.get(key).isJsonNull();
    }

    static String string(@Nullable JsonObject source, String key, String fallback) {
        JsonElement element = field(source, key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : fallback;
    }

    static boolean bool(@Nullable JsonObject source, String key, boolean fallback) {
        JsonElement element = field(source, key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : fallback;
    }

    static int intValue(@Nullable JsonObject source, String key, int fallback) {
        JsonElement element = field(source, key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : fallback;
    }

    static long longValue(@Nullable JsonObject source, String key, long fallback) {
        JsonElement element = field(source, key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsLong() : fallback;
    }

    @Nullable
    static JsonObject objectOf(@Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    static JsonArray arrayOf(@Nullable JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    // 读取 type 字段的路径部分，与 LootParseUtil.normalizeType 口径一致
    static String typeName(@Nullable JsonObject object) {
        if (object == null) {
            return "";
        }
        return LootParseUtil.normalizeType(string(object, "type", ""));
    }

    // 数组/对象字段的元素数量；无法判断时返回 -1（未知，而不是 0）
    static int listSize(@Nullable JsonObject source, String key) {
        JsonElement element = field(source, key);
        if (element == null || element.isJsonNull()) {
            return -1;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement value = object.get("value");
            if (value != null && value.isJsonArray()) {
                return value.getAsJsonArray().size();
            }
            return object.entrySet().size();
        }
        return -1;
    }

    static List<String> stringArray(@Nullable JsonObject source, String key) {
        JsonElement element = field(source, key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            values.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
        }
        return values;
    }

    @Nullable
    static ResourceLocation idOf(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return ResourceLocation.tryParse(element.getAsString());
    }

    // 文本/组件字段的可读摘要：字符串原样取用，其余形态退回有界 JSON 文本
    static String plainText(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return clip(element.getAsString());
        }
        return clip(element.toString());
    }

    // Filterable<String> 的原始文本：字符串直接用，对象取 raw 字段
    static String filterableText(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return clip(element.getAsString());
        }
        JsonObject object = objectOf(element);
        if (object != null) {
            String raw = string(object, "raw", "");
            if (!raw.isEmpty()) {
                return clip(raw);
            }
        }
        return clip(element.toString());
    }

    // 物品过滤器摘要：优先取 items/tag 这类稳定字段，取不到时退回有界 JSON 文本
    static String predicateText(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "unknown";
        }
        if (element.isJsonPrimitive()) {
            return clip(element.getAsString());
        }
        JsonObject object = objectOf(element);
        if (object != null) {
            String items = string(object, "items", "");
            String tag = string(object, "tag", "");
            if (!items.isEmpty() || !tag.isEmpty()) {
                return clip((items + " " + tag).trim());
            }
        }
        return clip(element.toString());
    }

    // ==================== 数值提供器 ====================

    @Nullable
    static Double numberOrNull(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsDouble();
    }

    // 可静态读取的数值提供器：常量返回单元素，区间返回两元素，读不出返回 null
    static double @Nullable [] knownRange(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return new double[]{element.getAsDouble()};
        }
        JsonObject object = objectOf(element);
        if (object == null) {
            return null;
        }
        String type = typeName(object);
        switch (type) {
            case "" -> {
                Double value = numberOrNull(object.get("value"));
                if (value != null) {
                    return new double[]{value};
                }
                Double min = numberOrNull(object.get("min"));
                Double max = numberOrNull(object.get("max"));
                return min == null || max == null ? null : new double[]{min, max};
            }
            case "constant" -> {
                Double value = numberOrNull(object.get("value"));
                return value == null ? null : new double[]{value};
            }
            case "uniform" -> {
                Double min = numberOrNull(object.get("min"));
                Double max = numberOrNull(object.get("max"));
                return min == null || max == null ? null : new double[]{min, max};
            }
            default -> {
                return null;
            }
        }
    }

    // 数值提供器展示文本：常量与范围给出具体值，其余一律按"动态"表达，绝不猜成固定值
    static Component numberText(@Nullable JsonElement element) {
        double[] range = knownRange(element);
        if (range == null) {
            return param("dynamic");
        }
        if (range.length == 1 || range[0] == range[1]) {
            return fnVariant("value", "constant", trimNumber(range[0]));
        }
        return fnVariant("value", "range", trimNumber(range[0]), trimNumber(range[1]));
    }

    // 数值提供器机器可读值：constant:<v> / range:<a>-<b> / dynamic
    static String numberMeta(@Nullable JsonElement element) {
        double[] range = knownRange(element);
        if (range == null) {
            return "dynamic";
        }
        if (range.length == 1 || range[0] == range[1]) {
            return "constant:" + trimNumber(range[0]);
        }
        return "range:" + trimNumber(range[0]) + "-" + trimNumber(range[1]);
    }

    // 整数化展示，避免写出 3.0 这种与声明不符的文本
    static String trimNumber(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ==================== 值类型构造 ====================

    // 机器可读元数据：奇数个参数时忽略末尾孤立项，值统一截断
    static Map<String, String> meta(String... keyValues) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            String key = keyValues[index];
            String value = keyValues[index + 1];
            if (key != null && value != null) {
                map.put(key, clip(value));
            }
        }
        return map;
    }

    // 完整保真的描述
    static LootFunctionInfo info(ResourceLocation type, Component description,
                                 FunctionEffectKind effect, String... metadata) {
        return info(type, description, FunctionFidelity.FULL, effect, metadata);
    }

    // 指定保真度的描述
    static LootFunctionInfo info(ResourceLocation type, Component description, FunctionFidelity fidelity,
                                 FunctionEffectKind effect, String... metadata) {
        return new LootFunctionInfo(type, description, fidelity, effect, List.of(), List.of(), meta(metadata));
    }

    static LootFunctionInfo withChildren(LootFunctionInfo base, List<LootFunctionInfo> children) {
        return new LootFunctionInfo(base.functionType(), base.description(), base.fidelity(), base.effect(),
                base.conditions(), children, base.metadata());
    }

    static LootFunctionInfo withFidelity(LootFunctionInfo base, FunctionFidelity fidelity) {
        return new LootFunctionInfo(base.functionType(), base.description(), fidelity, base.effect(),
                base.conditions(), base.children(), base.metadata());
    }

    // 取函数注册名；注册表不可用时退回处理器已知的路径，保证描述类型字段非 null
    static ResourceLocation typeId(LootItemFunction function, String fallbackPath) {
        ResourceLocation id = LootFunctionHandlers.keyOf(function);
        return id != null ? id : ResourceLocation.fromNamespaceAndPath("minecraft", fallbackPath);
    }

    // ==================== 包装展开 ====================

    // 递归描述内层函数：优先结构化描述，失败退回兼容摘要，最后才标记未解析；深度超限直接降级。
    // 内层函数自身的 conditions 由本方法**就地补齐**：条件解析需要 RegistryOps，而 describe 的冻结签名
    // 没有 ops，因此改为从 LootFunctionDescriptions.conditionOps() 取投影层安装的条件解析上下文。
    // 运行时捕获路径不安装该上下文（conditionOps()==null），内层条件保持为空——运行时对象本就没有静态 JSON。
    @Nullable
    static LootFunctionInfo describeNested(@Nullable LootItemFunction function, @Nullable JsonElement element) {
        if (function == null) {
            return null;
        }
        int depth = NESTED_DEPTH.get();
        ResourceLocation id = LootFunctionHandlers.keyOf(function);
        if (depth >= MAX_NESTED_DEPTH) {
            return LootFunctionDescriptions.unresolved(id, function)
                    .withMetadata(LootFunctionInfo.METADATA_ANALYSIS_INCOMPLETE, "depth")
                    .withMetadata(LootFunctionInfo.METADATA_TRUNCATED, "depth");
        }
        LootFunctionHandler handler = id != null ? LootFunctionHandlers.get(id) : null;
        if (handler == null) {
            return LootFunctionDescriptions.unresolved(id, function);
        }
        JsonObject childJson = LootFunctionDescriptions.normalizeSource(element);
        NESTED_DEPTH.set(depth + 1);
        try {
            if (childJson != null) {
                LootFunctionInfo described = handler.describe(function, childJson);
                if (described != null) {
                    // 内层函数自己的条件原位挂到子节点上——它不是外层物品的掉落条件（F04）
                    return withParsedConditions(described, childJson);
                }
            }
            Component hint = handler.describeHint(function, childJson != null ? childJson : new JsonObject());
            if (hint != null) {
                return new LootFunctionInfo(id, hint, FunctionFidelity.PARTIAL, FunctionEffectKind.UNKNOWN);
            }
        } catch (RuntimeException ignored) {
            // 内层描述失败不得影响外层描述，继续降级
        } finally {
            NESTED_DEPTH.set(depth);
        }
        return LootFunctionDescriptions.unresolved(id, function);
    }

    // 用投影层安装的 RegistryOps 解析该节点的自身条件并原位挂上；无上下文或无条件时原样返回。
    // 解析失败保留描述并标记语义不完整，由场景规划停止数值模拟，避免回退真实天气/时间。
    private static LootFunctionInfo withParsedConditions(LootFunctionInfo described, JsonObject source) {
        DynamicOps<JsonElement> ops = LootFunctionDescriptions.conditionOps();
        if (ops == null || !source.has("conditions")) {
            return described;
        }
        try {
            List<LootConditionInfo> conditions = LootParseUtil.parseConditions(source, ops);
            return conditions.isEmpty() ? described : described.withConditions(conditions);
        } catch (RuntimeException ignored) {
            return described.withMetadata(LootFunctionInfo.METADATA_ANALYSIS_INCOMPLETE, "conditions");
        }
    }

    // id 列表的有界文本：元数据与展示共用同一份截断结果
    static String joinIds(List<String> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        int limit = Math.min(ids.size(), MAX_LIST_ITEMS);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(ids.get(index));
        }
        if (ids.size() > limit) {
            builder.append("…");
        }
        return clip(builder.toString());
    }
}
