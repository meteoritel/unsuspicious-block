package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 战利品表**不可用机制**的静态识别（决策 31）。
 * <p>
 * 有些表用本模组的填充模型根本无法忠实模拟，例如：
 * <ul>
 *   <li>{@code enchantment_level} 数字提供器与 {@code enchantment_active_check} 条件需要
 *       {@code ENCHANTMENT_LEVEL} / {@code ENCHANTMENT_ACTIVE} 两个参数，而它们只在附魔效果的
 *       5 个 paramSet 里 required，任何普通战利品表 paramSet 都不包含——真实抽取时这两个参数
 *       也不存在，硬填等于造出游戏里不可能发生的精确概率（用户否决了"强行补填"）；</li>
 *   <li>{@code barter}（{@code PIGLIN_BARTER}）的 allowed 集合只有 {@code THIS_ENTITY}，
 *       连 {@code ORIGIN} 都不允许，因此 {@code LootParams.Builder.create} 直接抛
 *       {@code IllegalArgumentException}。它的语义还要求 THIS_ENTITY 是猪灵，用假玩家填充仍然失真
 *       （决策 10：诚实地不追踪优于给出错误概率）。</li>
 * </ul>
 * 本类的职责是**在构建期就把它们识别出来**：不把这类表入队模拟（否则会在
 * {@code getRandomItems} 里抛异常后静默失败，玩家只看到满屏问号），而是标记为
 * {@code Unknown(UNPARSED)} 并输出一次可行动的诊断。
 */
public final class LootMechanismSupport {
    /** 读 {@code ENCHANTMENT_LEVEL} 的数字提供器。 */
    private static final String ENCHANTMENT_LEVEL_PROVIDER = "minecraft:enchantment_level";
    /** 读 {@code ENCHANTMENT_ACTIVE} 的条件。 */
    private static final String ENCHANTMENT_ACTIVE_CHECK = "minecraft:enchantment_active_check";
    /** JSON 中用来指名机制类型的字段名（条件用 condition，函数用 function，其它用 type）。 */
    private static final List<String> TYPE_FIELDS = List.of("condition", "function", "type");

    private LootMechanismSupport() {
    }

    /**
     * 判断该表能否被本模组模拟；不能时返回一句可写进日志的诊断，能则返回 {@code null}。
     *
     * @param declaredType 表声明的 {@code type}（paramSet id）；**空串按 vanilla 语义等价于 generic**
     * @param tableJson    该表的有效 JSON；缺失时只做 paramSet 判定
     */
    @Nullable
    public static String diagnoseUnavailable(String declaredType, @Nullable JsonElement tableJson) {
        LootContextParamSet paramSet = paramSetOf(declaredType);
        if (paramSet == null) {
            return "表声明的 paramSet「" + declaredType + "」无法识别，填充模型无法保证参数完整";
        }
        // 允许集合为空（如 vanilla 的 empty）时，真实抽取本就不提供任何参数，模拟同样什么都不填，
        // 因此它可被忠实复现——不要因为它"不允许 ORIGIN"就误判为不可用。
        if (!paramSet.getAllowed().isEmpty() && !paramSet.isAllowed(LootContextParams.ORIGIN)) {
            return "paramSet「" + declaredType + "」不允许 ORIGIN 参数，与模拟填充模型不兼容";
        }
        List<String> unsupported = unsupportedMechanisms(tableJson, paramSet);
        if (!unsupported.isEmpty()) {
            return "引用了该 paramSet 不允许的机制: " + String.join(", ", unsupported);
        }
        return null;
    }

    /**
     * 该表 JSON 里引用了、但当前 paramSet 不提供其参数的机制 id。
     * <p>
     * 判定用两个条件同时成立：JSON 里出现了机制的 type 名，且该机制需要的参数不在 paramSet 的
     * allowed 集合里。只看类型名会把附魔效果自己的战利品表误判（那些表确实提供这两个参数）。
     */
    public static List<String> unsupportedMechanisms(@Nullable JsonElement tableJson,
                                                     LootContextParamSet paramSet) {
        if (tableJson == null) {
            return List.of();
        }
        Set<String> typeNames = new LinkedHashSet<>();
        collectTypeNames(tableJson, typeNames);
        List<String> unsupported = new ArrayList<>();
        if (typeNames.contains(ENCHANTMENT_LEVEL_PROVIDER)
                && !paramSet.isAllowed(LootContextParams.ENCHANTMENT_LEVEL)) {
            unsupported.add(ENCHANTMENT_LEVEL_PROVIDER);
        }
        if (typeNames.contains(ENCHANTMENT_ACTIVE_CHECK)
                && !paramSet.isAllowed(LootContextParams.ENCHANTMENT_ACTIVE)) {
            unsupported.add(ENCHANTMENT_ACTIVE_CHECK);
        }
        return List.copyOf(unsupported);
    }

    // 按表声明的 type 解析 paramSet；未知类型返回 null，由调用方按"无法保证"处理。
    //
    // 空串必须映射到 generic（ALL_PARAMS）而不是"无法识别"：vanilla 的 LootTable.DIRECT_CODEC 是
    // `LootContextParamSets.CODEC.lenientOptionalFieldOf("type", DEFAULT_PARAM_SET)`，而
    // DEFAULT_PARAM_SET = ALL_PARAMS。因此"没写 type"的表在游戏里就是一个 generic 上下文的表，
    // 它完全可被模拟。把空串当成未知会让这类表被误判为不可用（实测 BetterArcheology 的 7 张
    // 宝箱表就是这种写法）。
    @Nullable
    private static LootContextParamSet paramSetOf(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return LootContextParamSets.ALL_PARAMS;
        }
        ResourceLocation id = ResourceLocation.tryParse(declaredType);
        if (id == null) {
            return null;
        }
        return LootContextParamSets.CODEC
                .parse(JsonOps.INSTANCE, new JsonPrimitive(id.toString()))
                .result()
                .orElse(null);
    }

    // 递归收集 JSON 中所有指名机制类型的字段值（condition / function / type）
    private static void collectTypeNames(JsonElement element, Set<String> output) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectTypeNames(child, output);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String field : TYPE_FIELDS) {
            JsonElement value = object.get(field);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                output.add(value.getAsString());
            }
        }
        for (var child : object.entrySet()) {
            collectTypeNames(child.getValue(), output);
        }
    }
}
