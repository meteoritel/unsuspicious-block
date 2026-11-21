package com.meteorite.unsuspiciousblock.core.pottery;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.NotNull;

/** 原版熔炉与机器配方共用的制陶产物规则。 */
public enum PotteryFiring implements StringRepresentable {
    POT("pot"),
    SHERD("sherd");

    public static final Codec<PotteryFiring> CODEC = StringRepresentable.fromEnum(PotteryFiring::values);

    private final String serializedName;

    PotteryFiring(String serializedName) {
        this.serializedName = serializedName;
    }

    /** 根据坯件的纹饰组件生成一份成品。缺少纹饰时使用普通陶罐或砖块。 */
    public ItemStack fire(ItemStack unfired) {
        PotDecorations decorations = unfired.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);
        return switch (this) {
            case POT -> DecoratedPotBlockEntity.createDecoratedPotItem(decorations);
            case SHERD -> new ItemStack(decorations.back().orElse(Items.BRICK));
        };
    }

    @Override
    public @NotNull String getSerializedName() {
        return serializedName;
    }
}
