package com.meteorite.unsuspiciousblock.core.effect;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.function.BiConsumer;

/**
 * 喵步状态效果——提高安全下落高度并减免摔落伤害，同时配合静音服务压制脚步声与幽匿振动。
 *
 * <p>一二级共用恒定 +3 格安全下落高度（超过 6 格才扣血），摔落减伤非线性提升（一级 50%，二级 80%），
 * 因此覆写属性修饰符生命周期而非使用原版按等级线性叠加的 {@link #addAttributeModifier}。
 */
public class CatfootedEffect extends MobEffect {
    private static final int COLOR = 0x925CE0;
    private static final double SAFE_FALL_DISTANCE_BONUS = 3.0D;
    private static final double BASE_FALL_DAMAGE_REDUCTION = -0.5D;
    private static final double STRONG_FALL_DAMAGE_REDUCTION = -0.8D;
    private static final int STRONG_AMPLIFIER = 1;

    private static final ResourceLocation SAFE_FALL_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "catfooted_safe_fall_distance");
    private static final ResourceLocation FALL_DAMAGE_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "catfooted_fall_damage_multiplier");

    public CatfootedEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }

    @Override
    public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> output) {
        super.createModifiers(amplifier, output);
        output.accept(Attributes.SAFE_FALL_DISTANCE, createSafeFallModifier());
        output.accept(Attributes.FALL_DAMAGE_MULTIPLIER, createFallDamageModifier(amplifier));
    }

    @Override
    public void addAttributeModifiers(AttributeMap attributeMap, int amplifier) {
        super.addAttributeModifiers(attributeMap, amplifier);
        createModifiers(amplifier, (attribute, modifier) -> {
            AttributeInstance instance = attributeMap.getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
                instance.addTransientModifier(modifier);
            }
        });
    }

    @Override
    public void removeAttributeModifiers(AttributeMap attributeMap) {
        super.removeAttributeModifiers(attributeMap);
        removeModifier(attributeMap, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_MODIFIER_ID);
        removeModifier(attributeMap, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_DAMAGE_MODIFIER_ID);
    }

    private static AttributeModifier createSafeFallModifier() {
        return new AttributeModifier(SAFE_FALL_MODIFIER_ID, SAFE_FALL_DISTANCE_BONUS,
                AttributeModifier.Operation.ADD_VALUE);
    }

    private static AttributeModifier createFallDamageModifier(int amplifier) {
        double reduction = amplifier >= STRONG_AMPLIFIER ? STRONG_FALL_DAMAGE_REDUCTION : BASE_FALL_DAMAGE_REDUCTION;
        return new AttributeModifier(FALL_DAMAGE_MODIFIER_ID, reduction,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    private static void removeModifier(AttributeMap attributeMap, Holder<Attribute> attribute, ResourceLocation id) {
        AttributeInstance instance = attributeMap.getInstance(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
