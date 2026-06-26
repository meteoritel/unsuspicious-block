package com.meteorite.unsuspiciousblock.entity;

import com.meteorite.unsuspiciousblock.entity.ai.ghost.GhostCatBehavior;
import com.meteorite.unsuspiciousblock.entity.ai.ghost.GhostCatGiftGoal;
import com.meteorite.unsuspiciousblock.entity.ai.ghost.GhostCatPhase;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 幽灵猫 —— 继承原版猫，但半透明、发光、无实体碰撞、不可交互、穿墙飘行。
 * <p>
 * 由「古国往礼」等场景召唤，行为由注入的 {@link GhostCatBehavior} 策略驱动，
 * 阶段机 {@link GhostCatGiftGoal} 统一推进生命周期。新场景只需实现 Behavior 接口，
 * 无需改动实体本身。
 */
public class GhostCat extends Cat {

    // ========== 同步数据索引（客户端渲染所需，不依赖 behavior） ==========
    // 当前阶段序号（GhostCatPhase.ordinal()）
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(GhostCat.class, EntityDataSerializers.INT);
    // 当前阶段开始时的实体 tickCount（用于客户端推算阶段已持续 tick 数）
    private static final EntityDataAccessor<Integer> DATA_PHASE_START_TICK =
            SynchedEntityData.defineId(GhostCat.class, EntityDataSerializers.INT);
    // 显现阶段时长（tick），由 behavior 注入时同步给客户端
    private static final EntityDataAccessor<Integer> DATA_MANIFEST_DUR =
            SynchedEntityData.defineId(GhostCat.class, EntityDataSerializers.INT);
    // 消散阶段时长（tick），由 behavior 注入时同步给客户端
    private static final EntityDataAccessor<Integer> DATA_DISSIPATE_DUR =
            SynchedEntityData.defineId(GhostCat.class, EntityDataSerializers.INT);

    // ========== 行为策略（仅服务端持有，客户端不感知） ==========
    @Nullable
    private GhostCatBehavior behavior;

    // ========== 生命周期状态（仅服务端权威推进） ==========
    private GhostCatPhase phase = GhostCatPhase.MANIFEST;
    private int phaseTicks;
    private int totalTicks;

    // 召唤时记录的 Y，作为 move() 穿墙位移的底部夹紧基准，
    // 防止任何残余向下速度将灵体一路带到基岩层
    private double spawnY = Double.NaN;

    public GhostCat(EntityType<? extends Cat> type, Level level) {
        super(type, level);
        // 召唤而来，不参与自然刷新清理
        this.setPersistenceRequired();
        // 灵体不受重力影响，悬停飘行
        this.setNoGravity(true);
    }

    // 记录召唤初始 Y 供 move() 夹紧使用；由调用方（CatGiftService / 调试指令）在 moveTo 后触发
    public void freezeSpawnY() {
        this.spawnY = this.getY();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, GhostCatPhase.MANIFEST.ordinal());
        builder.define(DATA_PHASE_START_TICK, 0);
        builder.define(DATA_MANIFEST_DUR, 10);
        builder.define(DATA_DISSIPATE_DUR, 15);
    }

    // 仅注册阶段机驱动 AI，剔除原版猫的全部其他行为
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new GhostCatGiftGoal(this));
    }

    // ========== 行为注入 ==========

    // 注入行为策略，由召唤方在生成后立即调用；同时把渲染所需时长同步给客户端
    public void assignBehavior(GhostCatBehavior behavior) {
        this.behavior = behavior;
        this.entityData.set(DATA_MANIFEST_DUR, Math.max(1, behavior.getManifestDuration()));
        this.entityData.set(DATA_DISSIPATE_DUR, Math.max(1, behavior.getDissipateDuration()));
    }

    @Nullable
    public GhostCatBehavior getBehavior() {
        return this.behavior;
    }

    // ========== 生命周期推进（由阶段机调用，仅服务端） ==========

    // 重置生命周期，从显现阶段开始
    public void beginLifecycle() {
        this.phase = GhostCatPhase.MANIFEST;
        this.phaseTicks = 0;
        this.totalTicks = 0;
        this.syncPhaseToClient();
    }

    // 推进一 tick：阶段计数与总计数同步递增
    public void advanceTick() {
        this.phaseTicks++;
        this.totalTicks++;
    }

    // 切换阶段并重置阶段计数，清空残余速度防止阶段间漂移，同时同步给客户端
    public void setPhase(GhostCatPhase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
        // 阶段切换时清空 deltaMovement，避免 APPROACH→GREET 残余速度把猫推进玩家身体
        this.setDeltaMovement(Vec3.ZERO);
        this.syncPhaseToClient();
    }

    public GhostCatPhase getPhase() {
        return this.phase;
    }

    public int getPhaseTicks() {
        return this.phaseTicks;
    }

    public int getTotalTicks() {
        return this.totalTicks;
    }

    // 把当前阶段与起始 tick 写入同步数据，供客户端独立计算 alpha
    private void syncPhaseToClient() {
        this.entityData.set(DATA_PHASE, this.phase.ordinal());
        this.entityData.set(DATA_PHASE_START_TICK, this.tickCount);
    }

    // ========== 渲染辅助（客户端可调用，完全基于同步数据） ==========

    /**
     * 返回当前 alpha 占比（0~1），供渲染器在显现/消散阶段做渐入渐出。
     * MANIFEST: 0→1；DISSIPATE: 1→0；其他阶段: 1。
     * <p>
     * 客户端通过同步的 phase 序号、阶段起始 tick 与阶段时长独立推算，
     * 不依赖服务端独占的 behavior 字段，避免客户端 behavior==null 导致 alpha=0 不可见。
     */
    public float getAlphaProgress(float partialTick) {
        int phaseOrdinal = this.entityData.get(DATA_PHASE);
        GhostCatPhase renderPhase;
        try {
            renderPhase = GhostCatPhase.values()[phaseOrdinal];
        } catch (ArrayIndexOutOfBoundsException ex) {
            return 1.0F;
        }
        if (renderPhase != GhostCatPhase.MANIFEST && renderPhase != GhostCatPhase.DISSIPATE) {
            return 1.0F;
        }
        int startTick = this.entityData.get(DATA_PHASE_START_TICK);
        float elapsed = Math.max(0.0F, this.tickCount - startTick + partialTick);
        int dur = renderPhase == GhostCatPhase.MANIFEST
                ? Math.max(1, this.entityData.get(DATA_MANIFEST_DUR))
                : Math.max(1, this.entityData.get(DATA_DISSIPATE_DUR));
        return renderPhase == GhostCatPhase.MANIFEST
                ? Mth.clamp(elapsed / dur, 0.0F, 1.0F)
                : Mth.clamp(1.0F - elapsed / dur, 0.0F, 1.0F);
    }

    // ========== 灵体物理 ==========

    // 硬编码无重力：flag 可能被 vanilla 路径（finalizeSpawn/读盘等）重置，
    // 直接 override 返回 true 作为兜底，确保 travel() 不会施加 -0.08/t 的向下速度
    @Override
    public boolean isNoGravity() {
        return true;
    }

    // 跳过 vanilla 重力/摩擦/流体物理：灵体位移完全由 goal 通过 setDeltaMovement 驱动，
    // 这里只保留 move(SELF, deltaMovement) 把速度作用到位置（经下方 move() override 穿墙）
    @Override
    public void travel(@NotNull Vec3 travelVector) {
        if (this.isEffectiveAi()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
        }
    }

    // 全向穿墙位移：直接 setPos 跳过方块碰撞与实体碰撞推开逻辑。
    // Y 下限取"世界底部 + 1"与"召唤 Y"的较大者，防止残余向下速度把猫沉到基岩层
    @Override
    public void move(@NotNull MoverType type, @NotNull Vec3 delta) {
        double newY = this.getY() + delta.y;
        double minY = this.level().getMinBuildHeight() + 1.0;
        if (!Double.isNaN(this.spawnY) && this.spawnY > minY) {
            minY = this.spawnY;
        }
        if (newY < minY) {
            newY = minY;
        }
        this.setPos(this.getX() + delta.x, newY, this.getZ() + delta.z);
    }

    // ========== 不可交互边界 ==========

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canCollideWith(@NotNull Entity other) {
        return false;
    }

    @Override
    public void push(@NotNull Entity entity) {
    }

    // 禁用实体间推力：vanilla 的 LivingEntity.pushEntities() 会遍历附近 isPushable 的实体
    // 并调用 doPush → entity.push(this)，对玩家施加远离猫的力（即"猫把人往后推"现象）。
    // 灵体既不推人也不被推，整体 no-op
    @Override
    public void pushEntities() {
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    public static AttributeSupplier.@NotNull Builder createAttributes() {
        return Cat.createAttributes();
    }
}
