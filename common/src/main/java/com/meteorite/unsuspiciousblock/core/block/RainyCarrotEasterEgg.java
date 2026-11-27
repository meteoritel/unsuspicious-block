package com.meteorite.unsuspiciousblock.core.block;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.core.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 雨天农田胡萝卜彩蛋逻辑：
 * 雨天或雷雨天、玩家与成熟胡萝卜植株均处于露天淋雨状态时，破坏完全成熟的胡萝卜有概率触发天降红砖，
 * 将本次掉落的胡萝卜减少 1 个并换作上下两半胡萝卜与红砖，同时对玩家造成穿透防御的固定伤害并授予隐藏进度。
 */
public final class RainyCarrotEasterEgg {

    private static final float BASE_TRIGGER_CHANCE = 0.03F;
    private static final float HOE_TRIGGER_CHANCE = 0.10F;
    private static final float BRICK_DAMAGE = 3.0F;

    private static final float SOUND_VOLUME = 0.85F;
    private static final float SOUND_PITCH = 0.9F;
    private static final int CROP_PARTICLE_COUNT = 10;
    private static final int PLAYER_PARTICLE_COUNT = 6;
    private static final double DROP_SPREAD = 0.25D;

    /** 专属伤害类型：配合 damage_type 标签无视护甲、抗性、附魔与盾牌。 */
    public static final ResourceKey<DamageType> CARROT_BRICK_DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "carrot_brick")
    );

    private RainyCarrotEasterEgg() {
    }

    /**
     * 在基于物品栈列表的方块掉落流程中尝试触发彩蛋（供 Fabric 平台调用）。
     *
     * @param level   服务端世界
     * @param pos     被破坏方块坐标
     * @param state   被破坏方块状态
     * @param breaker 破坏者实体
     * @param tool    使用的工具
     * @param drops   原始掉落物品栈列表
     * @return 原列表（未触发）或替换后的可变掉落列表（已触发）
     */
    public static List<ItemStack> tryApplyToStacks(ServerLevel level, BlockPos pos, BlockState state,
                                                   @Nullable Entity breaker, ItemStack tool, List<ItemStack> drops) {
        ServerPlayer player = resolveEligiblePlayer(level, pos, state, breaker);
        if (player == null || drops.isEmpty() || !hasCarrotStack(drops) || !rollChance(level, tool)) {
            return drops;
        }

        List<ItemStack> modified = new ArrayList<>(drops.size() + 3);
        boolean consumedCarrot = false;
        for (ItemStack stack : drops) {
            if (!consumedCarrot && stack.is(Items.CARROT) && !stack.isEmpty()) {
                consumedCarrot = true;
                if (stack.getCount() > 1) {
                    modified.add(stack.copyWithCount(stack.getCount() - 1));
                }
                continue;
            }
            modified.add(stack);
        }

        modified.add(new ItemStack(ModItems.HALF_CARROT_TOP));
        modified.add(new ItemStack(ModItems.HALF_CARROT_BOTTOM));
        modified.add(new ItemStack(Items.BRICK));
        applySideEffects(level, pos, player);
        return modified;
    }

    /**
     * 在基于掉落实体列表的方块掉落事件中尝试触发彩蛋（供 NeoForge 平台调用）。
     *
     * @param level   服务端世界
     * @param pos     被破坏方块坐标
     * @param state   被破坏方块状态
     * @param breaker 破坏者实体
     * @param tool    使用的工具
     * @param drops   事件持有的可修改掉落物实体列表
     */
    public static void tryApplyToDropEntities(ServerLevel level, BlockPos pos, BlockState state,
                                              @Nullable Entity breaker, ItemStack tool, List<ItemEntity> drops) {
        ServerPlayer player = resolveEligiblePlayer(level, pos, state, breaker);
        if (player == null || drops.isEmpty() || !hasCarrotEntity(drops) || !rollChance(level, tool)) {
            return;
        }

        for (int i = 0; i < drops.size(); i++) {
            ItemEntity entity = drops.get(i);
            ItemStack stack = entity.getItem();
            if (stack.is(Items.CARROT) && !stack.isEmpty()) {
                if (stack.getCount() > 1) {
                    stack.shrink(1);
                    entity.setItem(stack);
                } else {
                    drops.remove(i);
                }
                break;
            }
        }

        drops.add(createDropEntity(level, pos, new ItemStack(ModItems.HALF_CARROT_TOP)));
        drops.add(createDropEntity(level, pos, new ItemStack(ModItems.HALF_CARROT_BOTTOM)));
        drops.add(createDropEntity(level, pos, new ItemStack(Items.BRICK)));
        applySideEffects(level, pos, player);
    }

    @Nullable
    private static ServerPlayer resolveEligiblePlayer(ServerLevel level, BlockPos pos, BlockState state,
                                                      @Nullable Entity breaker) {
        if (ModItems.HALF_CARROT_TOP == null || ModItems.HALF_CARROT_BOTTOM == null) {
            return null;
        }
        if (!(breaker instanceof ServerPlayer player) || player.isCreative()) {
            return null;
        }
        if (!state.is(Blocks.CARROTS) || !(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
            return null;
        }
        // 晴天直接短路，避免每次破坏都查询高度图与群系降水类型
        if (!level.isRaining() && !level.isThundering()) {
            return null;
        }
        if (!level.isRainingAt(pos) || !isPlayerInRain(level, player)) {
            return null;
        }
        return player;
    }

    // 耕地高 15/16 格，站在耕地上的玩家 blockPosition() 会落在耕地方块内部导致单点 isRainingAt 误判，
    // 因此沿用原版 Entity#isInRain 的双点口径同时校验脚部与包围盒顶部。
    private static boolean isPlayerInRain(ServerLevel level, ServerPlayer player) {
        BlockPos feetPos = player.blockPosition();
        if (level.isRainingAt(feetPos)) {
            return true;
        }
        BlockPos topPos = BlockPos.containing(feetPos.getX(), player.getBoundingBox().maxY, feetPos.getZ());
        return level.isRainingAt(topPos);
    }

    private static boolean rollChance(ServerLevel level, ItemStack tool) {
        float chance = isHoe(tool) ? HOE_TRIGGER_CHANCE : BASE_TRIGGER_CHANCE;
        return level.getRandom().nextFloat() < chance;
    }

    private static boolean isHoe(ItemStack tool) {
        return !tool.isEmpty() && (tool.is(ItemTags.HOES) || tool.getItem() instanceof HoeItem);
    }

    private static boolean hasCarrotStack(List<ItemStack> drops) {
        for (ItemStack stack : drops) {
            if (stack.is(Items.CARROT) && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCarrotEntity(List<ItemEntity> drops) {
        for (ItemEntity entity : drops) {
            ItemStack stack = entity.getItem();
            if (stack.is(Items.CARROT) && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static ItemEntity createDropEntity(ServerLevel level, BlockPos pos, ItemStack stack) {
        double halfItemHeight = EntityType.ITEM.getHeight() / 2.0D;
        double x = pos.getX() + 0.5D + Mth.nextDouble(level.random, -DROP_SPREAD, DROP_SPREAD);
        double y = pos.getY() + 0.5D + Mth.nextDouble(level.random, -DROP_SPREAD, DROP_SPREAD) - halfItemHeight;
        double z = pos.getZ() + 0.5D + Mth.nextDouble(level.random, -DROP_SPREAD, DROP_SPREAD);
        ItemEntity entity = new ItemEntity(level, x, y, z, stack);
        entity.setDefaultPickUpDelay();
        return entity;
    }

    private static void applySideEffects(ServerLevel level, BlockPos pos, ServerPlayer player) {
        level.playSound(null, pos, SoundEvents.DECORATED_POT_BREAK, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH);

        BlockParticleOption brickParticle = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BRICKS.defaultBlockState());
        level.sendParticles(brickParticle,
                pos.getX() + 0.5D, pos.getY() + 0.4D, pos.getZ() + 0.5D,
                CROP_PARTICLE_COUNT, 0.25D, 0.2D, 0.25D, 0.08D);
        level.sendParticles(brickParticle,
                player.getX(), player.getEyeY() + 0.15D, player.getZ(),
                PLAYER_PARTICLE_COUNT, 0.2D, 0.15D, 0.2D, 0.06D);

        // 先授予进度再造成伤害，确保残血被砸死时同样能记录首次触发
        AchievementManager.grantIfNotAlready(player, ModAchievements.BIGGEST_IN_THE_PATCH);
        player.hurt(createDamageSource(level), BRICK_DAMAGE);
    }

    private static DamageSource createDamageSource(ServerLevel level) {
        Registry<DamageType> registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return registry.getHolder(CARROT_BRICK_DAMAGE_TYPE)
                .map(DamageSource::new)
                .orElseGet(() -> level.damageSources().generic());
    }
}
