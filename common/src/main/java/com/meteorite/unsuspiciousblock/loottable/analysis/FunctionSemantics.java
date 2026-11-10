package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler.UncertaintyLevel;

/** 函数描述携带的策略事实；预览、投影与展示共用同一解释。 */
public record FunctionSemantics(FunctionEffectKind effect, FunctionFidelity fidelity,
                                Randomness randomness, UncertaintyLevel uncertainty,
                                IdentityImpact identity, boolean displayNoOp,
                                boolean emptyEnchantments) {
    public static final String METADATA_KEY = "semantics_v1";
    public static final int MAX_METADATA = 32;
    private static final List<String> REQUIRED_KEYS = List.of(METADATA_KEY,
            LootFunctionInfo.METADATA_ANALYSIS_INCOMPLETE, LootFunctionInfo.METADATA_TRUNCATED,
            LootFunctionInfo.METADATA_DISPLAY_NO_OP);

    public enum Randomness { NONE, RANDOM, UNKNOWN }
    public enum IdentityImpact { NONE, COMPONENTS, ITEM, UNKNOWN }

    static boolean isEncoded(LootFunctionInfo info) {
        return decode(info) != null;
    }

    public static FunctionSemantics read(LootFunctionInfo info) {
        FunctionSemantics decoded = decode(info);
        return decoded != null ? decoded : facts(info, Randomness.UNKNOWN, UncertaintyLevel.RUNTIME,
                defaultIdentity(info.effect()), false);
    }

    private static FunctionSemantics decode(LootFunctionInfo info) {
        String encoded = info.metadata().get(METADATA_KEY);
        if (encoded == null) return null;
        String[] fields = encoded.split("\\|", -1);
        if (fields.length != 4 || !(fields[3].equals("true") || fields[3].equals("false"))) return null;
        try {
            return new FunctionSemantics(info.effect(), info.fidelity(), Randomness.valueOf(fields[0]),
                    UncertaintyLevel.valueOf(fields[1]), IdentityImpact.valueOf(fields[2]),
                    "true".equals(info.metadata().get(LootFunctionInfo.METADATA_DISPLAY_NO_OP)),
                    Boolean.parseBoolean(fields[3]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    Map<String, String> encode(Map<String, String> source) {
        Map<String, String> metadata = new LinkedHashMap<>(source);
        metadata.put(METADATA_KEY, randomness.name() + "|" + uncertainty.name() + "|" + identity.name()
                + "|" + emptyEnchantments);
        if (displayNoOp) metadata.put(LootFunctionInfo.METADATA_DISPLAY_NO_OP, "true");
        else metadata.remove(LootFunctionInfo.METADATA_DISPLAY_NO_OP);
        return metadata;
    }

    /** 必需事实先于辅助键保留；同一输入的线路裁剪与 Map 遍历次序无关。 */
    public static Map<String, String> wireMetadata(Map<String, String> source) {
        Map<String, String> bounded = new LinkedHashMap<>(source);
        if (source.size() > MAX_METADATA || source.entrySet().stream()
                .anyMatch(entry -> entry.getKey().length() > 64 || entry.getValue().length() > 512)) {
            bounded.putIfAbsent(LootFunctionInfo.METADATA_TRUNCATED, "metadata");
        }
        Map<String, String> result = new LinkedHashMap<>();
        bounded.entrySet().stream().sorted(Comparator
                .comparingInt((Map.Entry<String, String> entry) -> {
                    int index = REQUIRED_KEYS.indexOf(entry.getKey());
                    return index < 0 ? REQUIRED_KEYS.size() : index;
                }).thenComparing(Map.Entry::getKey))
                .filter(entry -> entry.getKey().length() <= 64)
                .limit(MAX_METADATA).forEach(entry -> result.put(entry.getKey(),
                        entry.getValue().substring(0, Math.min(512, entry.getValue().length()))));
        return result;
    }

    static FunctionSemantics facts(LootFunctionInfo info, Randomness randomness,
                                   UncertaintyLevel uncertainty, IdentityImpact identity,
                                   boolean emptyEnchantments) {
        return new FunctionSemantics(info.effect(), info.fidelity(), randomness, uncertainty,
                identity, false, emptyEnchantments);
    }

    static IdentityImpact defaultIdentity(FunctionEffectKind effect) {
        return switch (effect) {
            case COUNT -> IdentityImpact.NONE;
            case COMPONENT, CONTAINER -> IdentityImpact.COMPONENTS;
            case ITEM_TRANSFORM -> IdentityImpact.ITEM;
            default -> IdentityImpact.UNKNOWN;
        };
    }

    static LootFunctionInfo deterministic(LootFunctionInfo info) {
        return info.withSemantics(facts(info, Randomness.NONE, UncertaintyLevel.NONE,
                defaultIdentity(info.effect()), false));
    }

    static LootFunctionInfo contextual(LootFunctionInfo info) {
        return info.withSemantics(facts(info, Randomness.NONE, UncertaintyLevel.RUNTIME,
                defaultIdentity(info.effect()), false));
    }

    static LootFunctionInfo random(LootFunctionInfo info) {
        return info.withSemantics(facts(info, Randomness.RANDOM, UncertaintyLevel.PROBABILISTIC,
                defaultIdentity(info.effect()), false));
    }

    static LootFunctionInfo unknown(LootFunctionInfo info) {
        return info.withSemantics(facts(info, Randomness.UNKNOWN, UncertaintyLevel.RUNTIME,
                defaultIdentity(info.effect()), false));
    }

    static LootFunctionInfo number(LootFunctionInfo info, JsonElement provider) {
        double[] range = LootFunctionDescribeSupport.knownRange(provider);
        if (range == null) return unknown(info);
        return range.length == 1 || range[0] == range[1] ? deterministic(info) : random(info);
    }

    static LootFunctionInfo enchantments(LootFunctionInfo info, JsonElement declaration) {
        if (declaration == null || !declaration.isJsonObject()) return unknown(info);
        Randomness randomness = Randomness.NONE;
        for (JsonElement provider : declaration.getAsJsonObject().asMap().values()) {
            FunctionSemantics child = number(info, provider).semantics();
            if (child.randomness.ordinal() > randomness.ordinal()) randomness = child.randomness;
        }
        return info.withSemantics(facts(info, randomness, UncertaintyLevel.RUNTIME, IdentityImpact.ITEM,
                declaration.getAsJsonObject().isEmpty()));
    }

    static LootFunctionInfo enchanting(LootFunctionInfo info) {
        return info.withSemantics(facts(info, Randomness.RANDOM, UncertaintyLevel.PROBABILISTIC,
                IdentityImpact.ITEM, false));
    }

    static LootFunctionInfo wrapper(LootFunctionInfo info, boolean conditional) {
        if (info.fidelity() != FunctionFidelity.FULL) return unknown(info);
        Randomness randomness = Randomness.NONE;
        UncertaintyLevel uncertainty = conditional ? UncertaintyLevel.RUNTIME : UncertaintyLevel.NONE;
        IdentityImpact identity = IdentityImpact.NONE;
        for (LootFunctionInfo child : info.children()) {
            FunctionSemantics semantics = child.semantics();
            if (semantics.randomness.ordinal() > randomness.ordinal()) randomness = semantics.randomness;
            if (semantics.uncertainty.ordinal() > uncertainty.ordinal()) uncertainty = semantics.uncertainty;
            if (child.hasConditions()) uncertainty = UncertaintyLevel.RUNTIME;
            if (semantics.identity.ordinal() > identity.ordinal()) identity = semantics.identity;
        }
        return info.withSemantics(facts(info, randomness, uncertainty, identity, false));
    }

    /** 空附魔映射对普通书仍有转换效果；前序变换也可能产生普通书。 */
    public LootFunctionInfo proveNoOp(LootFunctionInfo info, ItemStack input,
                                      List<LootFunctionInfo> preceding) {
        if (!emptyEnchantments || input.is(Items.BOOK)
                || preceding.stream().anyMatch(previous -> previous.semantics().mayChangeItem())) return info;
        return info.withSemantics(new FunctionSemantics(effect, fidelity, Randomness.NONE,
                UncertaintyLevel.NONE, IdentityImpact.NONE, true, true));
    }

    public boolean mayChangeItem() {
        return !displayNoOp && (identity == IdentityImpact.ITEM || identity == IdentityImpact.UNKNOWN);
    }

    public boolean affectsIdentity() {
        return !displayNoOp && identity != IdentityImpact.NONE;
    }

    public UncertaintyLevel failureUncertainty() {
        return uncertainty == UncertaintyLevel.NONE && effect != FunctionEffectKind.COUNT
                ? UncertaintyLevel.RUNTIME : uncertainty;
    }
}
