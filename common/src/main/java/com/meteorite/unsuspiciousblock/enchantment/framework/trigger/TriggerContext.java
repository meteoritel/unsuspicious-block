package com.meteorite.unsuspiciousblock.enchantment.framework.trigger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** 统一触发上下文——由 Platform Adapter 构建，承载事件相关数据并传递给 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager} */
public class TriggerContext {
    public final ServerPlayer player;
    public final ServerLevel level;
    @Nullable
    public final BlockPos pos;
    @Nullable
    public final BlockState blockState;
    @Nullable
    public final ItemStack tool;
    @Nullable
    public final Entity targetEntity;

    private TriggerContext(Builder builder) {
        this.player = builder.player;
        this.level = builder.level;
        this.pos = builder.pos;
        this.blockState = builder.blockState;
        this.tool = builder.tool;
        this.targetEntity = builder.targetEntity;
    }

    public static Builder builder(ServerPlayer player, ServerLevel level) {
        return new Builder(player, level);
    }

    public static class Builder {
        private final ServerPlayer player;
        private final ServerLevel level;
        private BlockPos pos;
        private BlockState blockState;
        private ItemStack tool;
        private Entity targetEntity;

        private Builder(ServerPlayer player, ServerLevel level) {
            this.player = player;
            this.level = level;
        }

        public Builder pos(BlockPos pos) {
            this.pos = pos;
            return this;
        }

        public Builder blockState(BlockState state) {
            this.blockState = state;
            return this;
        }

        public Builder tool(ItemStack tool) {
            this.tool = tool;
            return this;
        }

        public Builder targetEntity(Entity entity) {
            this.targetEntity = entity;
            return this;
        }

        public TriggerContext build() {
            return new TriggerContext(this);
        }
    }
}
