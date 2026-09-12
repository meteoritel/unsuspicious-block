package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.item.CopperPanItem;
import com.meteorite.unsuspiciousblock.pan.ShimmerLedger;
import com.meteorite.unsuspiciousblock.pan.ShimmerPlacement;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/***
 * 闪烁的光——淘盘专属的水面淘洗点实体。
 * <p>
 * 该实体依附于其下方的水方块而存在：位置永远锚定在水方块上，水方块被破坏或被方块占据时立刻消散，
 * 且不参与碰撞、不受流体推动、不可被攻击破坏。它只响应淘盘，被淘空后消散。
 * <p>
 * 按来源分为两类：
 * <ul>
 *     <li>自然生成——每维度受数量上限约束，拥有 20~40 分钟随机寿命，未加载区块不计时，
 *     寿命耗尽前若玩家位于其 3 格内则延长 30 秒。</li>
 *     <li>世界生成——供玩家探索时发现，不自然消散也不计入数量上限。</li>
 * </ul>
 * 渲染完全依赖水面粒子，淘洗次数越少粒子越稀疏，用于向玩家暗示剩余价值。
 */
public class ShimmerEntity extends Entity {
    private static final String NBT_PAN_REMAINING = "PanRemaining";
    private static final String NBT_NATURAL_SPAWN = "NaturalSpawn";
    private static final String NBT_LIFETIME_TICKS = "LifetimeTicks";
    private static final String NBT_ANCHOR = "AnchorPos";

    // 剩余可淘洗次数——同步到客户端用于分档粒子表现
    private static final EntityDataAccessor<Integer> DATA_PAN_REMAINING =
            SynchedEntityData.defineId(ShimmerEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_PANNING =
            SynchedEntityData.defineId(ShimmerEntity.class, EntityDataSerializers.BOOLEAN);
    private long lastPanningTick = Long.MIN_VALUE;
    private int clientWorkTicks;

    // 玩家位于该半径内时，寿命到期不再直接消散，而是延长宽限时间
    private static final double GRACE_RADIUS = 3.0D;
    // 宽限延长的刻数（30 秒）
    private static final int GRACE_EXTENSION_TICKS = 600;

    private boolean naturalSpawn = true;
    private boolean ledgerRegistered;
    // 剩余寿命刻数，仅在实体处于加载状态时递减；世界生成来源恒为 0（不消散）
    private int lifetimeTicks;
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
    }

    // 在指定水方块上初始化淘洗点；worldgen 来源不设置寿命，永不自然消散
    public void initializeAt(BlockPos waterPos, boolean natural, int lifetimeTicks) {
        this.anchorPos = waterPos.immutable();
        this.naturalSpawn = natural;
        this.lifetimeTicks = natural ? Math.max(0, lifetimeTicks) : 0;
        this.setPanRemaining(Services.PANNING_CONFIG.getPanUses());
        this.snapToAnchor();
    }

    public int getPanRemaining() {
        return this.entityData.get(DATA_PAN_REMAINING);
    }

    private void setPanRemaining(int remaining) {
        this.entityData.set(DATA_PAN_REMAINING, Math.max(0, remaining));
    }

    // 是否由自然生成产生——世界生成的淘洗点不受上限与寿命约束
    public boolean isNaturalSpawn() {
        return this.naturalSpawn;
    }

    // 返回其依附的水方块坐标
    public BlockPos getAnchorPos() {
        return this.anchorPos;
    }

    // 只有服务端验证过的有效淘洗才能刷新工作状态；不持久化临时演出状态。
    public void markPanning() {
        if (!this.level().isClientSide() && !this.isRemoved() && this.getPanRemaining() > 0) {
            this.lastPanningTick = this.level().getGameTime();
            this.entityData.set(DATA_PANNING, true);
        }
    }

    public boolean isPanning() {
        return this.entityData.get(DATA_PANNING);
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
        if (!(stack.getItem() instanceof CopperPanItem) || this.getPanRemaining() <= 0) {
            return InteractionResult.PASS;
        }
        // 由淘盘的长按流程接管后续进度；此处只负责进入使用状态
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    // 淘洗一次，返回是否成功消耗；次数耗尽时立刻消散
    public boolean consumePanUse() {
        if (this.level().isClientSide() || this.getPanRemaining() <= 0) {
            return false;
        }
        int remaining = this.getPanRemaining() - 1;
        this.setPanRemaining(remaining);
        this.level().playSound(null, this.anchorPos, SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS, 0.8F, remaining <= 0 ? 0.6F : 1.0F + (3 - remaining) * 0.15F);
        if (remaining <= 0) {
            this.discard();
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
        // 允许实体与玩家 tick 顺序相差一刻；停止操作后最多两刻恢复闲置。
        this.entityData.set(DATA_PANNING, this.lastPanningTick != Long.MIN_VALUE
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
                    this.naturalSpawn ? ShimmerLedger.Source.NATURAL : ShimmerLedger.Source.WORLDGEN);
            this.ledgerRegistered = true;
        }
        this.tickLifetime();
    }

    // 寿命只在实体加载时递减，天然满足“未加载区块不计时”
    private void tickLifetime() {
        if (!this.naturalSpawn || this.lifetimeTicks <= 0) {
            return;
        }
        this.lifetimeTicks--;
        if (this.lifetimeTicks > 0) {
            return;
        }
        // 到期时若玩家就在近旁，则延长宽限时间，避免在玩家眼前消散
        if (this.hasPlayerWithinGrace()) {
            this.lifetimeTicks = GRACE_EXTENSION_TICKS;
            return;
        }
        this.discard();
    }

    private boolean hasPlayerWithinGrace() {
        return !this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(GRACE_RADIUS)).isEmpty();
    }

    // 粒子作为贴水金色波光的点缀，工作时增加旋转水花与向外扩散的涟漪。
    private void clientTick() {
        int remaining = this.getPanRemaining();
        if (remaining <= 0) {
            return;
        }
        RandomSource random = this.level().getRandom();
        this.clientWorkTicks = this.isPanning() ? this.clientWorkTicks + 1 : 0;
        int phaseTick = this.tickCount + this.getId();
        // 剩余 3/2/1 次对应约 15/6.7/2 个白色粒子每秒，形成明显的密度档位。
        int glintInterval = remaining >= 3 ? 4 : (remaining == 2 ? 6 : 10);
        int glintCount = Math.min(3, remaining);
        if (phaseTick % glintInterval == 0) {
            for (int i = 0; i < glintCount; i++) {
                this.level().addParticle(ParticleTypes.END_ROD,
                        this.getX() + (random.nextDouble() - 0.5D) * 0.85D,
                        this.waterSurfaceY() + 0.025D,
                        this.getZ() + (random.nextDouble() - 0.5D) * 0.85D,
                        0.0D, 0.0D, 0.0D);
            }
        }
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

    // 水面高度：水方块顶面约在方块底部 +0.875
    private double waterSurfaceY() {
        return this.getY() + 0.875D;
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
        tag.putBoolean(NBT_NATURAL_SPAWN, this.naturalSpawn);
        tag.putInt(NBT_LIFETIME_TICKS, this.lifetimeTicks);
        tag.putLong(NBT_ANCHOR, BlockPos.asLong(this.anchorPos.getX(), this.anchorPos.getY(),
                this.anchorPos.getZ()));
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        this.setPanRemaining(tag.contains(NBT_PAN_REMAINING)
                ? tag.getInt(NBT_PAN_REMAINING) : Services.PANNING_CONFIG.getPanUses());
        this.naturalSpawn = !tag.contains(NBT_NATURAL_SPAWN) || tag.getBoolean(NBT_NATURAL_SPAWN);
        this.lifetimeTicks = Math.max(0, tag.getInt(NBT_LIFETIME_TICKS));
        this.anchorPos = tag.contains(NBT_ANCHOR) ? BlockPos.of(tag.getLong(NBT_ANCHOR)) : this.blockPosition();
        this.snapToAnchor();
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
        level.sendParticles(ParticleTypes.SPLASH,
                this.getX(), this.waterSurfaceY(), this.getZ(),
                8, 0.35D, 0.05D, 0.35D, 0.0D);
        level.playSound(null, this.anchorPos, SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS, 0.7F, 1.4F);
    }
}
