package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link SimulationInput} ↔ 稳定字符串。
 * <p>
 * 编码形态是 {@code 字段=值} 用 {@code |} 连接、字段顺序固定：
 * <pre>
 * scenario=baseline|luck=0.00|tool=minecraft:brush|ench=|n=10000
 * scenario=cond:3a9f10c2e48b1102|luck=3.00|tool=minecraft:fishing_rod|ench=minecraft:luck_of_the_sea:3|n=10000
 * </pre>
 * 基准场景保持字面量 {@code baseline}，勾选至少一项环境条件时对规范排序后的条件 ID 列表取 SHA-256 前 16 位，
 * 保证键长度有界且跨重启确定一致，同时与末段 {@code |n=...} 的 LRU 分组逻辑兼容。
 */
public final class SimulationInputKey {
    private static final char FIELD_SEPARATOR = '|';
    private static final String CONDITION_KEY_PREFIX = "cond:";

    private SimulationInputKey() {
    }

    /** 生成规范化的场景段标识（{@code baseline} 或 {@code cond:<16位hex>}）。 */
    public static String scenarioSegment(List<String> enabledConditions) {
        List<String> canonical = SimulationInput.canonicalize(enabledConditions);
        if (canonical.isEmpty()) {
            return SimulationScenarioPlanner.BASELINE_SCENARIO_KEY;
        }
        return CONDITION_KEY_PREFIX + shortSha256(String.join(",", canonical));
    }

    /** 生成完整规范缓存键。 */
    public static String of(List<String> enabledConditions, ScenarioParams params) {
        return ofScenarioSegment(scenarioSegment(enabledConditions), params);
    }

    /** 根据已算好的场景段标识与参数生成完整规范键。 */
    public static String ofScenarioSegment(String scenarioSegment, ScenarioParams params) {
        StringBuilder builder = new StringBuilder(96);
        builder.append("scenario=").append(scenarioSegment);
        builder.append(FIELD_SEPARATOR).append("luck=")
                .append(String.format(Locale.ROOT, "%.2f", params.luck()));
        builder.append(FIELD_SEPARATOR).append("tool=").append(params.toolId());
        builder.append(FIELD_SEPARATOR).append("ench=");
        appendEnchantments(builder, params.toolEnchantments());
        builder.append(FIELD_SEPARATOR).append("n=").append(params.sampleCount());
        return builder.toString();
    }

    /**
     * 输入的参数组合键——去掉抽样次数段 {@code |n=...} 后的键，用于缓存 LRU 淘汰分组。
     */
    public static String parameterGroup(String inputKey) {
        int lastSeparator = inputKey.lastIndexOf(FIELD_SEPARATOR);
        if (lastSeparator <= 0) {
            return inputKey;
        }
        String lastField = inputKey.substring(lastSeparator + 1);
        return lastField.startsWith("n=") ? inputKey.substring(0, lastSeparator) : inputKey;
    }

    private static void appendEnchantments(StringBuilder builder, Map<ResourceLocation, Integer> levels) {
        boolean first = true;
        for (Map.Entry<ResourceLocation, Integer> entry : levels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).toList()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey()).append(':').append(entry.getValue());
        }
    }

    private static String shortSha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}
