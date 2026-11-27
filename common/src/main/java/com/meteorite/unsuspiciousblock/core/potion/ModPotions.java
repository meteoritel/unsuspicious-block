package com.meteorite.unsuspiciousblock.core.potion;

import com.meteorite.unsuspiciousblock.core.bootstrap.Registrar;
import com.meteorite.unsuspiciousblock.core.effect.ModEffects;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组药水变体注册清单——声明灵猫药水的基础、红石延长与萤石强化三档原版 {@link Potion} 实例，
 * 由各平台在状态效果注册完成后统一回写 {@link Holder}。
 */
public final class ModPotions {
    // 药水时长与等级常量（对齐原版标准 3:00 / 8:00 / 1:30 节拍）
    private static final int BASE_DURATION_TICKS = 3600;
    private static final int LONG_DURATION_TICKS = 9600;
    private static final int STRONG_DURATION_TICKS = 1800;
    private static final int BASE_AMPLIFIER = 0;
    private static final int STRONG_AMPLIFIER = 1;
    private static final String POTION_BASE_NAME = "catfooted";

    public static Holder<Potion> PURRMINT;
    public static Holder<Potion> LONG_PURRMINT;
    public static Holder<Potion> STRONG_PURRMINT;

    /** 药水注册清单条目，供各平台遍历注册并回写 Holder。 */
    public record PotionEntry(String name, Supplier<Potion> factory, Consumer<Holder<Potion>> setter) {
    }

    public static final List<PotionEntry> REGISTRY_MANIFEST = List.of(
            new PotionEntry("purrmint", ModPotions::createBase, holder -> PURRMINT = holder),
            new PotionEntry("long_purrmint", ModPotions::createLong, holder -> LONG_PURRMINT = holder),
            new PotionEntry("strong_purrmint", ModPotions::createStrong, holder -> STRONG_PURRMINT = holder)
    );

    private ModPotions() {
    }

    /** 创建基础灵猫药水变体（喵步 I，3:00）。 */
    public static Potion createBase() {
        return new Potion(POTION_BASE_NAME,
                new MobEffectInstance(ModEffects.CATFOOTED, BASE_DURATION_TICKS, BASE_AMPLIFIER));
    }

    /** 创建延长灵猫药水变体（喵步 I，8:00）。 */
    public static Potion createLong() {
        return new Potion(POTION_BASE_NAME,
                new MobEffectInstance(ModEffects.CATFOOTED, LONG_DURATION_TICKS, BASE_AMPLIFIER));
    }

    /** 创建强化灵猫药水变体（喵步 II，1:30）。 */
    public static Potion createStrong() {
        return new Potion(POTION_BASE_NAME,
                new MobEffectInstance(ModEffects.CATFOOTED, STRONG_DURATION_TICKS, STRONG_AMPLIFIER));
    }

    /** 遍历清单并调用平台注册回调。 */
    public static void forEach(Registrar<Potion, Holder<Potion>> registrar) {
        for (PotionEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }
}
