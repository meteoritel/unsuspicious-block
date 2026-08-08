package com.meteorite.unsuspiciousblock.block;

import com.meteorite.unsuspiciousblock.blockentity.UnfiredDecoratedPotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** 未烧制纹饰陶罐方块，纹饰由方块实体保存并在烧制时转换为原版陶罐。 */
public class UnfiredDecoratedPotBlock extends Block implements EntityBlock {
    public UnfiredDecoratedPotBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new UnfiredDecoratedPotBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            net.minecraft.world.entity.LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof UnfiredDecoratedPotBlockEntity pot) {
            pot.setFromItem(stack);
        }
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level,
                                                        @NotNull BlockPos pos, @NotNull Player player,
                                                        @NotNull BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    @Override
    protected @NotNull List<ItemStack> getDrops(@NotNull BlockState state, @NotNull LootParams.Builder params) {
        if (params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof UnfiredDecoratedPotBlockEntity pot) {
            return List.of(pot.getPotAsItem());
        }
        return super.getDrops(state, params);
    }
}
