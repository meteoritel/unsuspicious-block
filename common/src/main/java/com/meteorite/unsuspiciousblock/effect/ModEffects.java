package com.meteorite.unsuspiciousblock.effect;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 模组效果--效果实例由各平台分别创建注册，Holder 回写至静态字段供 common 代码引用 */
public class ModEffects {

    // 猫之九命：触发后的 2 秒纯无敌窗口标记。
    public static Holder<MobEffect> CAT_FAVOR;

    // 效果注册清单条目，供各平台遍历注册
    public record EffectEntry(String name, Supplier<MobEffect> factory, Consumer<Holder<MobEffect>> setter) {}

    // 效果注册清单--新增效果只需在此添加一行
    public static final List<EffectEntry> REGISTRY_MANIFEST = List.of(
            new EffectEntry("cat_favor", ModEffects::createCatFavor, holder -> CAT_FAVOR = holder)
    );

    // 创建猫之恩惠实例
    public static CatFavorEffect createCatFavor() {
        return new CatFavorEffect();
    }

    // 遍历清单，调用平台回调完成注册
    public static void forEach(MobEffectRegistrar registrar) {
        for (EffectEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }
}
