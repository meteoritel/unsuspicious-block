package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;

/** 条件的语义身份或本代出处身份；opaque 出处只用于保序去重，不作为运行时覆盖键。 */
public record ConditionIdentity(String key, boolean semantic) implements Comparable<ConditionIdentity> {
    /** 手工声明或无法编码的条件使用独立出处，复制节点时保留该值。 */
    public static ConditionIdentity occurrence() {
        return new ConditionIdentity(UUID.randomUUID().toString(), false);
    }

    /** 使用实际注册表上下文编码条件；编码失败的扩展保留 opaque 出处。 */
    public static ConditionIdentity ofRaw(LootItemCondition condition, DynamicOps<JsonElement> ops) {
        try {
            JsonElement encoded = LootItemCondition.DIRECT_CODEC.encodeStart(ops, condition).result().orElse(null);
            if (encoded != null) return new ConditionIdentity(hash(canonical(encoded).toString()), true);
        } catch (RuntimeException exception) {
            // 第三方 Codec 可以要求未提供的上下文；这种条件仍展示，但不伪造可覆盖身份。
        }
        return occurrence();
    }

    /** 节点身份不依赖描述文本、对象地址或展示行。 */
    public static ConditionIdentity of(LootConditionInfo info) {
        return info.identity();
    }

    /** 只有可复现语义身份可参与场景覆盖。 */
    public static boolean isStable(LootConditionInfo info) {
        return info.identity().semantic();
    }

    @Override
    public int compareTo(ConditionIdentity other) {
        int compared = key.compareTo(other.key);
        return compared != 0 ? compared : Boolean.compare(semantic, other.semantic);
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.add(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(child -> array.add(canonical(child)));
            return array;
        }
        return value;
    }

    private static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}
