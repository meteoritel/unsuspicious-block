package com.meteorite.unsuspiciousblock.core.block;

import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/** 咕噜荆芥幼苗，在耕地上生长并于最后一次生长时替换为成熟植株。 */
public class PurrmintCropBlock extends CropBlock {
    public static final MapCodec<PurrmintCropBlock> CODEC = simpleCodec(PurrmintCropBlock::new);
    public static final IntegerProperty AGE = BlockStateProperties.AGE_2;
    public static final int MAX_AGE = 3;
    private static final int GROWTH_SKIP_BOUND = 3;
    private static final float GROWTH_CHANCE_SCALE = 25.0F;
    private static final int STAGE_INCREASE = 1;
    private static final float BASE_GROWTH_SPEED = 1.0F;
    private static final float DRY_SOIL_GROWTH = 1.0F;
    private static final float WET_SOIL_GROWTH = 3.0F;
    private static final float NEIGHBOR_SOIL_DIVISOR = 4.0F;
    private static final float CROWDED_CROP_DIVISOR = 2.0F;
    private static final VoxelShape[] SHAPE_BY_AGE = {
            Block.box(4.0, 0.0, 4.0, 12.0, 4.0, 12.0),
            Block.box(2.0, 0.0, 2.0, 14.0, 8.0, 14.0),
            Block.box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0)
    };

    public PurrmintCropBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull MapCodec<PurrmintCropBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.@NotNull Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }

    @Override
    protected @NotNull IntegerProperty getAgeProperty() {
        return AGE;
    }

    @Override
    public int getMaxAge() {
        return MAX_AGE;
    }

    @Override
    protected @NotNull ItemLike getBaseSeedId() {
        return ModItems.PURRMINT_SEEDS;
    }

    /** 自然生长、骨粉和蜜蜂共用成熟转换，成熟年龄不写入幼苗方块状态。 */
    @Override
    public @NotNull BlockState getStateForAge(int age) {
        return age >= MAX_AGE ? ModBlocks.PURRMINT.get().defaultBlockState() : super.getStateForAge(age);
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                          @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE_BY_AGE[getAge(state)];
    }

    @Override
    protected boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        // 作物只依赖耕地存活，避开 CropBlock 的亮度门槛。
        BlockPos soilPos = pos.below();
        return mayPlaceOn(level.getBlockState(soilPos), level, soilPos);
    }

    @Override
    protected void randomTick(@NotNull BlockState state, @NotNull ServerLevel level,
                              @NotNull BlockPos pos, @NotNull RandomSource random) {
        if (random.nextInt(GROWTH_SKIP_BOUND) == 0) {
            return;
        }
        // 保留火把花的生长节奏与耕地湿度计算，生长不依赖亮度。
        float growthSpeed = calculateGrowthSpeed(level, pos);
        if (random.nextInt((int) (GROWTH_CHANCE_SCALE / growthSpeed) + 1) == 0) {
            level.setBlock(pos, getStateForAge(getAge(state) + STAGE_INCREASE), Block.UPDATE_CLIENTS);
        }
    }

    // NeoForge 改变了原版辅助方法的签名，耕地算法在此共用以保持双平台一致。
    private float calculateGrowthSpeed(BlockGetter level, BlockPos pos) {
        float growthSpeed = BASE_GROWTH_SPEED;
        BlockPos soilPos = pos.below();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockState soil = level.getBlockState(soilPos.offset(x, 0, z));
                if (!soil.is(Blocks.FARMLAND)) {
                    continue;
                }
                float soilGrowth = soil.getValue(FarmBlock.MOISTURE) > 0 ? WET_SOIL_GROWTH : DRY_SOIL_GROWTH;
                if (x != 0 || z != 0) {
                    soilGrowth /= NEIGHBOR_SOIL_DIVISOR;
                }
                growthSpeed += soilGrowth;
            }
        }
        boolean hasEastWestCrop = level.getBlockState(pos.east()).is(this)
                || level.getBlockState(pos.west()).is(this);
        boolean hasNorthSouthCrop = level.getBlockState(pos.north()).is(this)
                || level.getBlockState(pos.south()).is(this);
        boolean hasDiagonalCrop = level.getBlockState(pos.north().east()).is(this)
                || level.getBlockState(pos.north().west()).is(this)
                || level.getBlockState(pos.south().east()).is(this)
                || level.getBlockState(pos.south().west()).is(this);
        return (hasEastWestCrop && hasNorthSouthCrop) || hasDiagonalCrop
                ? growthSpeed / CROWDED_CROP_DIVISOR : growthSpeed;
    }

    @Override
    protected int getBonemealAgeIncrease(@NotNull Level level) {
        return STAGE_INCREASE;
    }
}
