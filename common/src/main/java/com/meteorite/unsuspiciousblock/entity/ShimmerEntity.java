package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.item.CopperPanItem;
import com.meteorite.unsuspiciousblock.pan.ShimmerLedger;
import com.meteorite.unsuspiciousblock.pan.ShimmerPlacement;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/***
 * 闪烁的光——淘盘专属的水面淘洗点实体。
 * <p>
 * 该实体依附于其下方的水方块而存在：位置永远锚定在水方块上，水方块被破坏或被方块占据时立刻消散，
 * 且不参与碰撞、不受流体推动、不可被攻击破坏。它只响应淘盘，有限寿命来源被淘空后消散，世界生成来源保留并恢复次数。
 * <p>
 * 按来源分为三类：
 * <ul>
 *     <li>自然生成——每维度受数量上限约束，拥有 20~40 分钟绝对游戏时间寿命，卸载区块继续计时。</li>
 *     <li>世界生成——供玩家探索时发现，不自然消散也不计入数量上限，定时恢复一次淘洗次数。</li>
 *     <li>特殊生成——拥有有限寿命，不计入自然生成上限，不能继续触发特殊生成。</li>
 * </ul>
 * 渲染完全依赖水面粒子，淘洗次数越少粒子越稀疏，用于向玩家暗示剩余价值。
 */
public class ShimmerEntity extends Entity {
    private static final String NBT_PAN_REMAINING = "PanRemaining";
    private static final String NBT_NATURAL_SPAWN = "NaturalSpawn";
    private static final String NBT_SPECIAL_SPAWN = "SpecialSpawn";
    private static final String NBT_LIFETIME_TICKS = "LifetimeTicks";
    private static final String NBT_EXPIRES_AT = "ExpiresAt";
    private static final String NBT_INITIAL_PAN_USES = "InitialPanUses";
    private static final String NBT_RECOVERY_INTERVAL_TICKS = "RecoveryIntervalTicks";
    private static final String NBT_NEXT_RECOVERY_AT = "NextRecoveryAt";
    private static final String NBT_ANCHOR = "AnchorPos";

    // 剩余可淘洗次数——同步到客户端用于分档粒子表现
    private static final EntityDataAccessor<Integer> DATA_PAN_REMAINING =
            SynchedEntityData.defineId(ShimmerEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_PANNING =
            SynchedEntityData.defineId(ShimmerEntity.class, EntityDataSerializers.BOOLEAN);

    // 是否为世界生成来源——同步到客户端，用于采空后仍保留低频闪光
    private static final EntityDataAccessor<Boolean> DATA_WORLDGEN =
            SynchedEntityData.defineId(ShimmerEntity.class, EntityDataSerializers.BOOLEAN);

    private long lastPanningTick = Long.MIN_VALUE;
    private int clientWorkTicks;

    // 是否有寿命：世界生成来源恒为 false，它不自然消散也不计入自然生成上限。
    private boolean hasLifetime = true;
    private boolean specialSpawn;
    private boolean ledgerRegistered;
    // 绝对游戏时间截止点；世界生成来源恒为 0。
    private long expiresAt;
    // 世界生成来源的次数恢复：初始次数上限、固定恢复周期与下一恢复时刻。
    private int initialPanUses;
    private int recoveryIntervalTicks;
    private long nextRecoveryAt;

    private BlockPos anchorPos = BlockPos.ZERO;

    public ShimmerEntity(EntityType<? extends ShimmerEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        builder.define(DATA_PAN_REMAINING, Services.PANNING_CONFIG.getPanUses());
        builder.define(DATA_PANNING, false);
        builder.define(DATA_WORLDGEN, false);
    }

    // 在指定水方块上初始化淘洗点：有寿命来源用 lifetimeTicks 作绝对寿命，
    // 世界生成来源不设寿命（expiresAt 恒 0），改用同一个值作为淘洗次数的恢复周期。
    public void initializeAt(BlockPos waterPos, boolean hasLifetime, int lifetimeTicks) {
        this.anchorPos = waterPos.immutable();
        this.applySpawnSource(hasLifetime, false);
        this.expiresAt = hasLifetime ? this.level().getGameTime() + Math.max(1, lifetimeTicks) : 0;
        this.initialPanUses = Services.PANNING_CONFIG.getPanUses();
        this.recoveryIntervalTicks = hasLifetime ? 0 : Math.max(1, lifetimeTicks);
        this.nextRecoveryAt = hasLifetime ? 0 : this.level().getGameTime() + this.recoveryIntervalTicks;
        this.setPanRemaining(this.initialPanUses);
        this.snapToAnchor();
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    // 来源标记统一入口：特殊生成只能建立在有寿命的来源之上，世界生成标记恒与寿命相反。
    private void applySpawnSource(boolean hasLifetime, boolean special) {
        this.hasLifetime = hasLifetime;
        this.specialSpawn = hasLifetime && special;
        this.entityData.set(DATA_WORLDGEN, !hasLifetime);
    }

    // 特殊来源沿用有限寿命，但单独持久化分类以支持按类型清除。
    public void setSpecialSpawn(boolean special) {
        this.applySpawnSource(this.hasLifetime, special);
    }

    public ShimmerLedger.Source getSpawnSource() {
        if (!this.hasLifetime) return ShimmerLedger.Source.WORLDGEN;
        return this.specialSpawn ? ShimmerLedger.Source.SPECIAL : ShimmerLedger.Source.NATURAL;
    }

    // 仅由自然生成成功入口调用；按同维度三维球形距离通知，加载存档不重复提示。
    public void broadcastNaturalSpawn(ServerLevel level) {
        Component message = Component.translatable("message.unsuspiciousblock.shimmer.natural_spawn");
        for (var player : level.players()) {
            if (player.distanceToSqr(this) <= 32.0D * 32.0D) {
                player.sendSystemMessage(message);
            }
        }
    }

    // 交互也检查截止时间，避免实体与玩家 tick 顺序造成过期后仍可采集。
    private boolean discardIfExpired() {
        if (this.level() instanceof ServerLevel serverLevel
                && ((this.hasLifetime && this.level().getGameTime() >= this.expiresAt)
                || ShimmerLedger.of(serverLevel).isExpired(this.getUUID()))) {
            this.discard();
            return true;
        }
        return false;
    }

    public int getPanRemaining() {
        return this.entityData.get(DATA_PAN_REMAINING);
    }

    private void setPanRemaining(int remaining) {
        this.entityData.set(DATA_PAN_REMAINING, Math.max(0, remaining));
    }

    // 仅自然来源可触发特殊生成；特殊来源虽然有寿命，也不属于自然来源。
    public boolean isNaturalSpawn() {
        return this.hasLifetime && !this.specialSpawn;
    }

    // 返回其依附的水方块坐标
    public BlockPos getAnchorPos() {
        return this.anchorPos;
    }

    // 客户端使用同步后的位置，不能使用未同步的 anchorPos 字段判定冰面。
    public boolean isFrozen() {
        return this.level().getBlockState(this.blockPosition()).is(Blocks.ICE);
    }

    // 起手、持续淘洗和结算都即时检查水面，避免结冰与实体 tick 的先后顺序造成误结算。
    public boolean canPan() {
        BlockPos pos = this.level().isClientSide() ? this.blockPosition() : this.anchorPos;
        return !this.isRemoved() && this.getPanRemaining() > 0
                && this.level().getBlockState(pos).is(Blocks.WATER)
                && this.level().getBlockState(pos.above()).isAir();
    }

    // 只有服务端验证过的有效淘洗才能刷新工作状态；不持久化临时演出状态。
    public void markPanning() {
        if (!this.level().isClientSide() && this.canPan()) {
            this.lastPanningTick = this.level().getGameTime();
            this.entityData.set(DATA_PANNING, true);
        }
    }

    public boolean isPanning() {
        return this.entityData.get(DATA_PANNING) && this.canPan();
    }

    // 客户端演出时钟，供粒子与水声共用节奏。
    public int getWorkTicks() {
        return this.clientWorkTicks;
    }

    // ========== 交互 ==========

    // 仅接受淘盘：其余物品与空手一律让位给原版流程
    @Override
    public @NotNull InteractionResult interact(@NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (this.discardIfExpired() || !(stack.getItem() instanceof CopperPanItem) || !this.canPan()) {
            return InteractionResult.PASS;
        }
        if (player.isUsingItem()) {
            return InteractionResult.CONSUME;
        }
        // 只播放装水声，不调用水桶取水逻辑；服务端广播一次，避免双端重复播放。
        if (!this.level().isClientSide()) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8F, 1.0F);
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    // 淘洗一次，返回是否成功消耗；世界生成点耗尽后保留。
    public boolean consumePanUse() {
        if (this.level().isClientSide() || this.isRemoved() || this.discardIfExpired() || !this.canPan()) {
            return false;
        }
        int remaining = this.getPanRemaining() - 1;
        this.setPanRemaining(remaining);
        this.level().playSound(null, this.anchorPos, SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS, 0.8F, remaining <= 0 ? 0.6F : 1.0F + (3 - remaining) * 0.15F);
        if (remaining <= 0) {
            if (this.level() instanceof ServerLevel serverLevel) {
                ShimmerLedger.of(serverLevel).startHarvestCooldown(this.anchorPos,
                        serverLevel.getGameTime(), Services.PANNING_CONFIG.getHarvestCooldownTicks());
            }
            if (this.hasLifetime) this.discard();
        }
        return true;
    }

    // ========== 生命周期 ==========

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.clientTick();
            return;
        }
        this.serverTick();
    }

    private void serverTick() {
        if (this.discardIfExpired()) return;
        this.recoverPanUses();
        // 允许实体与玩家 tick 顺序相差一刻；停止操作后最多两刻恢复闲置。
        this.entityData.set(DATA_PANNING, this.canPan() && this.lastPanningTick != Long.MIN_VALUE
                && this.level().getGameTime() - this.lastPanningTick <= 1L);
        // 位置锚定：任何外力造成的偏移都会被立刻纠正，保证始终贴合绑定的水方块
        if (!this.blockPosition().equals(this.anchorPos)) {
            this.snapToAnchor();
        }
        // 绑定的水方块被破坏或被方块占据时，立刻消散且不产出任何东西
        if (!ShimmerPlacement.isBoundWaterIntact(this.level(), this.anchorPos)) {
            this.discard();
            return;
        }
        if (!this.ledgerRegistered && this.level() instanceof ServerLevel serverLevel) {
            ShimmerLedger.of(serverLevel).register(this.getUUID(), this.anchorPos,
                    this.getSpawnSource(), this.expiresAt);
            this.ledgerRegistered = true;
        }
    }

    // 按生成时固定的周期恢复；卸载期间继续计时，跨多个周期一次补算且不超过初始次数。
    private void recoverPanUses() {
        if (this.hasLifetime || this.recoveryIntervalTicks <= 0) return;
        long now = this.level().getGameTime();
        if (now < this.nextRecoveryAt) return;
        long cycles = (now - this.nextRecoveryAt) / this.recoveryIntervalTicks + 1;
        this.setPanRemaining((int) Math.min(this.initialPanUses, this.getPanRemaining() + cycles));
        this.nextRecoveryAt += cycles * this.recoveryIntervalTicks;
    }

    // 粒子作为贴水金色波光的点缀，工作时增加旋转水花与向外扩散的涟漪。
    private void clientTick() {
        // 世界生成点即使暂时采空也保留低频闪光，方便玩家辨认与再次寻找。
        if (this.entityData.get(DATA_WORLDGEN) && this.tickCount % 10 == 0) {
            this.level().addParticle(ParticleTypes.END_ROD,
                    this.getX() + (this.random.nextDouble() - 0.5D) * 0.7D,
                    this.waterSurfaceY() + 0.08D,
                    this.getZ() + (this.random.nextDouble() - 0.5D) * 0.7D,
                    0.0D, 0.015D, 0.0D);
        }
        int remaining = this.getPanRemaining();
        if (remaining <= 0) {
            return;
        }
        this.clientWorkTicks = this.isPanning() ? this.clientWorkTicks + 1 : 0;

        if (!this.isPanning() || this.clientWorkTicks % 2 != 0) {
            return;
        }
        float angle = this.clientWorkTicks * Mth.TWO_PI / 20.0F;
        // 每两刻仅发射两组粒子；多人淘洗同一点不会叠加发射数量。
        for (int i = 0; i < 2; i++) {
            float direction = angle + i * Mth.PI;
            double dx = Mth.cos(direction);
            double dz = Mth.sin(direction);
            this.level().addParticle(ParticleTypes.SPLASH,
                    this.getX() + dx * 0.3D, this.waterSurfaceY() + 0.06D,
                    this.getZ() + dz * 0.3D, dx * 0.025D, 0.055D, dz * 0.025D);
            this.level().addParticle(ParticleTypes.FISHING,
                    this.getX() + dx * 0.18D, this.waterSurfaceY() + 0.02D,
                    this.getZ() + dz * 0.18D, dx * 0.04D, 0.0D, dz * 0.04D);
        }
    }

    // 冰面位于完整方块顶面，水面约在方块底部 +0.875。
    private double waterSurfaceY() {
        return this.getY() + (this.isFrozen() ? 1.0D : 0.875D);
    }

    private void snapToAnchor() {
        this.setPos(this.anchorPos.getX() + 0.5D, this.anchorPos.getY(), this.anchorPos.getZ() + 0.5D);
        this.setDeltaMovement(Vec3.ZERO);
    }

    // 消散时从账本注销，保证上限计数与间距校验始终反映真实存活状态
    @Override
    public void remove(Entity.@NotNull RemovalReason reason) {
        if (!this.level().isClientSide()
                && (reason == RemovalReason.DISCARDED || reason == RemovalReason.KILLED)
                && this.level() instanceof ServerLevel serverLevel) {
            ShimmerLedger.of(serverLevel).unregister(this.getUUID());
        }
        super.remove(reason);
    }

    // ========== 存档 ==========

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        tag.putInt(NBT_PAN_REMAINING, this.getPanRemaining());
        tag.putBoolean(NBT_NATURAL_SPAWN, this.hasLifetime);
        tag.putBoolean(NBT_SPECIAL_SPAWN, this.specialSpawn);
        tag.putLong(NBT_EXPIRES_AT, this.expiresAt);
        tag.putInt(NBT_INITIAL_PAN_USES, this.initialPanUses);
        tag.putInt(NBT_RECOVERY_INTERVAL_TICKS, this.recoveryIntervalTicks);
        tag.putLong(NBT_NEXT_RECOVERY_AT, this.nextRecoveryAt);
        tag.putLong(NBT_ANCHOR, BlockPos.asLong(this.anchorPos.getX(), this.anchorPos.getY(),
                this.anchorPos.getZ()));
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        this.setPanRemaining(tag.contains(NBT_PAN_REMAINING)
                ? tag.getInt(NBT_PAN_REMAINING) : Services.PANNING_CONFIG.getPanUses());
        this.applySpawnSource(!tag.contains(NBT_NATURAL_SPAWN) || tag.getBoolean(NBT_NATURAL_SPAWN),
                tag.getBoolean(NBT_SPECIAL_SPAWN));
        this.initialPanUses = tag.contains(NBT_INITIAL_PAN_USES) ? Math.max(1, tag.getInt(NBT_INITIAL_PAN_USES))
                : Math.max(this.getPanRemaining(), Services.PANNING_CONFIG.getPanUses());
        if (!this.hasLifetime && this.level() instanceof ServerLevel serverLevel) {
            this.recoveryIntervalTicks = tag.getInt(NBT_RECOVERY_INTERVAL_TICKS);
            if (this.recoveryIntervalTicks <= 0) {
                this.recoveryIntervalTicks = ShimmerSpawnService.randomLifetimeTicks(serverLevel);
            }
            this.nextRecoveryAt = tag.contains(NBT_NEXT_RECOVERY_AT) ? tag.getLong(NBT_NEXT_RECOVERY_AT)
                    : this.level().getGameTime() + this.recoveryIntervalTicks;
            this.recoverPanUses();
        }
        this.expiresAt = this.hasLifetime ? (tag.contains(NBT_EXPIRES_AT)
                ? tag.getLong(NBT_EXPIRES_AT)
                : this.level().getGameTime() + Math.max(1, tag.getInt(NBT_LIFETIME_TICKS))) : 0;
        if (this.hasLifetime && this.level() instanceof ServerLevel serverLevel) {
            ShimmerLedger ledger = ShimmerLedger.of(serverLevel);
            this.expiresAt = Math.min(this.expiresAt, ledger.expirationOf(this.getUUID(), this.expiresAt));
        }
        this.anchorPos = tag.contains(NBT_ANCHOR) ? BlockPos.of(tag.getLong(NBT_ANCHOR)) : this.blockPosition();
        this.snapToAnchor();
        this.ledgerRegistered = false;
        this.discardIfExpired();
    }

    // ========== 物理与抗性 ==========

    // 可被准星选中，才能被淘盘瞄准
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canCollideWith(@NotNull Entity other) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public void push(double x, double y, double z) {
    }

    // 不可被任何攻击或爆炸破坏；只有方块占位、淘空与寿命耗尽能使其消散
    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(@NotNull DamageSource source) {
        return true;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    // 供外部在生成后补充一次入水演出
    public void playSpawnEffects(@Nullable ServerLevel level) {
        if (level == null) {
            return;
        }
        level.sendParticles(this.isFrozen() ? ParticleTypes.END_ROD : ParticleTypes.SPLASH,
                this.getX(), this.waterSurfaceY(), this.getZ(),
                8, 0.35D, 0.05D, 0.35D, 0.0D);
        level.playSound(null, this.anchorPos, SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS, 0.7F, 1.4F);
    }
}
