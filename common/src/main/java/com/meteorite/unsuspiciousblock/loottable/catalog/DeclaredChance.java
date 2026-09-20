package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 数据表明确声明的 random_chance 数值；表示单个随机条件的触发率，不是最终掉落率。 */
public record DeclaredChance(double lower, double upper) {
    public static final String MIN_KEY = "declared_chance_min";
    public static final String MAX_KEY = "declared_chance_max";
    private static final ResourceLocation RANDOM_CHANCE =
            ResourceLocation.withDefaultNamespace("random_chance");

    public DeclaredChance {
        if (!Double.isFinite(lower) || !Double.isFinite(upper)
                || lower < 0 || upper > 1 || lower > upper) {
            throw new IllegalArgumentException("触发率必须为 [0, 1] 内的有序有限区间");
        }
    }

    // 只读取解析层提供的结构化数据；多个条件和路径保序去重，不猜测权重或合成掉落率。
    public static List<DeclaredChance> fromPaths(List<LootAcquisitionPath> paths) {
        Set<DeclaredChance> values = new LinkedHashSet<>();
        for (LootAcquisitionPath path : paths) {
            collect(path.allConditions(), values);
        }
        return List.copyOf(values);
    }

    private static void collect(List<LootConditionInfo> conditions, Set<DeclaredChance> values) {
        for (LootConditionInfo condition : conditions) {
            if (RANDOM_CHANCE.equals(condition.conditionType())) {
                String min = condition.metadata().get(MIN_KEY);
                String max = condition.metadata().get(MAX_KEY);
                if (min != null && max != null) {
                    try {
                        values.add(new DeclaredChance(Double.parseDouble(min), Double.parseDouble(max)));
                    } catch (IllegalArgumentException ignored) {
                        // 第三方提供的非法元数据不能阻止目录展示，仍可回退到模拟值。
                    }
                }
            }
            collect(condition.children(), values);
        }
    }
}
