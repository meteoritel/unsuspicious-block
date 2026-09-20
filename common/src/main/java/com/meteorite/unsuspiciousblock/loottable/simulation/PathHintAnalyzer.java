package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从获取路径的条件树派生**静态信息性提示**，并据此把测量值提升为「需要条件」。
 * <p>
 * 只做静态判定：它回答的是"这条路径的条件树里出现没出现过可调整的旋钮或场景控制条件"，
 * 答案就在编译产物里，不需要任何搜索。**它不承诺可达成**——按决策 34，可点击的推荐必须由
 * 联合见证搜索产出，搜不到就只渲染本类给出的纯文本。
 * <p>
 * 本类同时承担"测量值 → 展示值"的派生（见 {@link #deriveDisplay}）。派生规则刻意保持保守：
 * <ol>
 *   <li>测到了非零值就报测量值——这是玩家最想要的那个事实；</li>
 *   <li>静态不可达照实报 {@code 0%}；</li>
 *   <li>表级失败（规则未解析、计算失败、尚未请求、已淘汰）原样透传，不被"需要条件"覆盖；</li>
 *   <li>零命中且路径引用了旋钮 → 「需要条件」。**这是 P0 的近似**：真正的可适用性判定需要
 *       逐路径最小幸运门槛与参数化判定（P1 的 {@code LuckGateAnalysis} 与两轴展示状态）。
 *       在只有布尔场景与固定旋钮的 P0，把"被旋钮挡住而抽样为零"说成「未命中」会是一句假话
 *       （决策 40 的同一类错误），因此优先报「需要条件」；</li>
 *   <li>零命中且路径不引用任何旋钮 → 「未命中」——这是老实的抽样陈述。</li>
 * </ol>
 */
public final class PathHintAnalyzer {
    private static final ResourceLocation MATCH_TOOL =
            ResourceLocation.withDefaultNamespace("match_tool");
    private static final ResourceLocation TABLE_BONUS =
            ResourceLocation.withDefaultNamespace("table_bonus");
    private static final ResourceLocation ENCHANTMENT_ACTIVE_CHECK =
            ResourceLocation.withDefaultNamespace("enchantment_active_check");
    private static final ResourceLocation RANDOM_CHANCE_WITH_ENCHANTED_BONUS =
            ResourceLocation.withDefaultNamespace("random_chance_with_enchanted_bonus");

    private PathHintAnalyzer() {
    }

    /** 把基准输入的测量值派生为展示值。 */
    public static Probability deriveDisplay(Probability baseline, List<LootAcquisitionPath> paths) {
        if (baseline instanceof Probability.NeedsCondition) {
            return baseline;
        }
        if (baseline instanceof Probability.Measured measured && measured.lower() > 0.0) {
            return baseline;
        }
        if (baseline instanceof Probability.Unreachable) {
            return baseline;
        }
        // 表级失败优先于"需要条件"：规则没解析出来时，说"需要某个条件"同样是编造
        if (baseline instanceof Probability.Unknown unknown
                && unknown.reason() != UnknownReason.UNCOVERED) {
            return baseline;
        }
        List<PathHint> hints = hintsFor(paths);
        if (!hints.isEmpty()) {
            return Probability.needsCondition(hints);
        }
        // 适用、抽样零命中、且路径不引用任何旋钮：这是真正的抽样结论
        return baseline instanceof Probability.Measured ? baseline : Probability.uncovered();
    }

    /**
     * 某条路径在当前场景下不可用时该展示什么。
     * <p>
     * D2 的拆分点：**不是**"静态不可达"，而是"这个场景的布尔赋值下不成立"。引用了可调整的
     * 旋钮或场景条件时展示为「需要条件」并列出引用目标，否则如实报"未覆盖"。
     * 模拟与存档恢复两条路径共用这一份判定，避免两处各写一套而漂移。
     */
    public static Probability inapplicableScenarioDisplay(List<PathHint> hints) {
        return hints.isEmpty() ? Probability.uncovered() : Probability.needsCondition(hints);
    }

    /**
     * 收集该条目全部获取路径引用到的旋钮与场景条件。
     * 参数提示按 {@link ParameterKind} 的声明顺序排在前，场景条件合并为一条排在最后——
     * 先读"要调什么"、再读"要满足什么"。
     */
    public static List<PathHint> hintsFor(List<LootAcquisitionPath> paths) {
        Map<ParameterKind, Map<String, Component>> parameterDetails = new EnumMap<>(ParameterKind.class);
        LinkedHashMap<String, LootConditionInfo> scenarioConditions = new LinkedHashMap<>();
        boolean luckReferenced = false;

        for (LootAcquisitionPath path : paths) {
            luckReferenced |= path.luckAffected();
            for (LootConditionInfo condition : path.allConditions()) {
                collect(condition, parameterDetails, scenarioConditions);
            }
        }

        List<PathHint> hints = new ArrayList<>();
        if (luckReferenced) {
            addParameter(parameterDetails, ParameterKind.LUCK, "", null);
        }
        for (ParameterKind kind : ParameterKind.values()) {
            Map<String, Component> details = parameterDetails.get(kind);
            if (details == null) {
                continue;
            }
            for (Component detail : details.values()) {
                hints.add(new PathHint.ReferencesParameter(kind, detail));
            }
        }
        if (!scenarioConditions.isEmpty()) {
            hints.add(new PathHint.ReferencesScenario(List.copyOf(scenarioConditions.values())));
        }
        return List.copyOf(hints);
    }

    // 递归分类单条条件；组合条件只按类型本身归类，其子条件各自递归
    private static void collect(LootConditionInfo condition,
                                Map<ParameterKind, Map<String, Component>> parameterDetails,
                                LinkedHashMap<String, LootConditionInfo> scenarioConditions) {
        ResourceLocation type = condition.conditionType();
        if (MATCH_TOOL.equals(type)) {
            // 决策 8：match_tool 不再由场景伪造布尔，工具真实求值，因此它是"工具"旋钮的引用
            addParameter(parameterDetails, ParameterKind.TOOL, detailKey(condition), condition.description());
        } else if (isEnchantmentLevelMechanism(type)) {
            // 附魔等级是函数/条件在运行时读取的输入（决策 26），不是条件谓词
            addParameter(parameterDetails, ParameterKind.ENCHANT_LEVEL, detailKey(condition),
                    condition.description());
        } else if (SimulationScenarioPlanner.isScenarioControlled(type)) {
            scenarioConditions.putIfAbsent(detailKey(condition), condition);
        }
        for (LootConditionInfo child : condition.children()) {
            collect(child, parameterDetails, scenarioConditions);
        }
    }

    // 读工具附魔等级的机制：模组的 tool_enchantment 与三处原版机制。等级是资格也是概率来源
    private static boolean isEnchantmentLevelMechanism(ResourceLocation type) {
        return type.equals(com.meteorite.unsuspiciousblock.loottable.condition.ModLootConditions.TOOL_ENCHANTMENT)
                || TABLE_BONUS.equals(type)
                || ENCHANTMENT_ACTIVE_CHECK.equals(type)
                || RANDOM_CHANCE_WITH_ENCHANTED_BONUS.equals(type);
    }

    private static void addParameter(Map<ParameterKind, Map<String, Component>> target,
                                     ParameterKind kind, String detailKey, @Nullable Component detail) {
        Map<String, Component> details = target.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
        // 无 detail 的引用用空串占位，保证 LUCK 这类"无具体目标"的旋钮只出现一次
        details.putIfAbsent(detailKey, detail == null ? Component.empty() : detail);
    }

    // 同一条件在解析期与运行时的实例不同，用类型 + 展示文本做稳定去重键（与指纹同型）
    private static String detailKey(LootConditionInfo condition) {
        String detail = condition.description().getString();
        return condition.conditionType() + "|" + detail;
    }
}
