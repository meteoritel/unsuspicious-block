package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 为解析期与运行时的战利品条件生成稳定指纹，使模拟场景可以精确覆盖同类型的不同条件。
 */
public final class LootConditionFingerprint {
    public static final String METADATA_KEY = "simulation_fingerprint";
    /** 解析期判定的指纹稳定性，供场景规划拒绝为不稳定指纹建立无效场景。 */
    public static final String STABLE_METADATA_KEY = "simulation_fingerprint_stable";

    private LootConditionFingerprint() {
    }

    // 从运行时条件生成与解析期一致的指纹
    public static String of(LootItemCondition condition) {
        List<LootConditionInfo> infos = LootConditionHandlers.analyzeAll(List.of(condition));
        if (infos.isEmpty()) {
            return hash(condition.getClass().getName() + '|' + condition);
        }
        return of(infos.getFirst());
    }

    // 优先使用分析阶段保存的原始条件指纹
    public static String of(LootConditionInfo info) {
        String stored = info.metadata().get(METADATA_KEY);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        StringBuilder source = new StringBuilder(info.conditionType().toString())
                .append('|').append(info.description());
        if (info.probability() != null) {
            source.append('|').append(info.probability());
        }
        info.metadata().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(METADATA_KEY))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> source.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
        for (LootConditionInfo child : info.children()) {
            source.append('|').append(of(child));
        }
        return hash(source.toString());
    }

    // 从原始条件对象生成指纹，避免展示文本丢失 ItemPredicate 等机器信息
    public static String ofRaw(LootItemCondition condition) {
        return hash(condition.getClass().getName() + '|' + condition);
    }

    /**
     * 判断条件对象的指纹来源是否稳定。
     * <p>
     * {@link #ofRaw} 依赖 {@code toString()}，只有按字段值生成文本的类型（record 即是）才能让
     * 解析期与运行时的两个不同实例得到同一指纹。未覆写 {@code toString()} 的类型会退化为
     * {@code 类名@identityHash}，两个阶段的指纹必然不同，场景覆盖会**静默**失效。
     * 此处按文本形态识别该默认实现，供场景规划提前排除此类条件。
     */
    public static boolean isStableSource(LootItemCondition condition) {
        return condition.getClass().isRecord() || !hasDefaultToString(condition);
    }

    // 从解析期写入的元数据读取指纹稳定性；缺失标记视为稳定（手工构造的条件信息不参与场景规划）
    public static boolean isStable(LootConditionInfo info) {
        String stored = info.metadata().get(STABLE_METADATA_KEY);
        return stored == null || Boolean.parseBoolean(stored);
    }

    // 判断 toString() 是否为 Object 默认形态（类名@十六进制哈希）
    private static boolean hasDefaultToString(Object value) {
        String text = value.toString();
        if (!text.startsWith(value.getClass().getName())) {
            return false;
        }
        int at = text.lastIndexOf('@');
        if (at < 0 || at == text.length() - 1) {
            return false;
        }
        for (int index = at + 1; index < text.length(); index++) {
            if (Character.digit(text.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String hash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
