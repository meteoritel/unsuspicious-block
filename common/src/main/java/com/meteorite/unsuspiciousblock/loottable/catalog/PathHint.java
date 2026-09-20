package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 单一路径的**静态信息性提示**——只陈述"这条路径引用了哪些可调旋钮与条件"，
 * 不承诺"照着调就能拿到"（决策 34）。
 * <p>
 * 之所以严格限制在"陈述引用关系"这一层：三个维度各自有解**并不等于**同一条路径存在共同解
 * （父路径要求幸运 ≥ 3、子路径要求幸运 ≤ 1 时，幸运维度与场景维度可以同时有解却没有任何可用的
 * 联合解）。因此任何"可点击的推荐"必须携带整条路径联合验证过的具体输入，那由 P2 的
 * {@code RecommendationSolver} 产出；本类型只负责无条件可给的那一半。
 */
public sealed interface PathHint {
    /** 该路径引用了场景控制类型的条件（群系、天气、时间、分数等）。 */
    record ReferencesScenario(List<LootConditionInfo> conditions) implements PathHint {
        public ReferencesScenario {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 该路径引用了某个可调旋钮。
     *
     * @param kind   旋钮种类
     * @param detail 引用目标的人类可读描述（附魔名、工具谓词原文等）；无具体目标时为 {@code null}。
     *               用 {@link Component} 而非字符串：文案必须本地化，把服务端语言固化进目录会让
     *               客户端在其它语言下读到错误文本。
     */
    record ReferencesParameter(ParameterKind kind, @Nullable Component detail) implements PathHint {
    }
}
