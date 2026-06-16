package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * 猫的威慑（恩惠≥20）——苦力怕会像惧怕猫一样躲避持有该被动的玩家。
 * 注入 Creeper.registerGoals 追加一个针对目标玩家的躲避目标，
 * 躲避条件由玩家的恩惠能力位掩码动态决定（可被玩家按键关闭）。
 * <p>
 * 继承自 {@link Mob}，使 goalSelector（声明于 Mob）以真实继承成员方式访问，
 * 避免 @Shadow 无法解析父类成员的问题。
 */
@Mixin(Creeper.class)
public abstract class CreeperAvoidCatFavorMixin extends Mob {

    // 仅为满足编译器对父类构造器的要求，Mixin 运行时不会使用此构造器
    protected CreeperAvoidCatFavorMixin(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void unsuspiciousblock$addCatFavorAvoidance(CallbackInfo ci) {
        Creeper creeper = (Creeper) (Object) this;
        Predicate<LivingEntity> deterred =
                living -> living instanceof Player player && CatPassiveAbilities.hasActiveDeterrence(player);
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(
                creeper, Player.class, deterred, 6.0F, 1.0, 1.2,
                EntitySelector.NO_CREATIVE_OR_SPECTATOR::test));
    }
}
