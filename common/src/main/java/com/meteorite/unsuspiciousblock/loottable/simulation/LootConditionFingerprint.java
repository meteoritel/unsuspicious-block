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

    private static String hash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
