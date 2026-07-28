package com.meteorite.unsuspiciousblock.entity.ai.spiritcat;

import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 幽灵猫行为策略 —— 决定各阶段时长、是否赠礼/致意，以及各阶段的视觉/声效副作用。
 * <p>
 * 这是幽灵猫的扩展点：未来「九命护灵」「击退袭击致意」等新场景只需实现此接口，
 * 由 {@link MessengerCatGiftGoal} 统一驱动阶段机，无需改动实体或阶段机本身。
 * <p>
 * 实现实例通常携带自身数据（目标 UUID、战利品表等），由调用方在召唤时构造并
 * 通过 {@link MessengerCat#assignBehavior(MessengerCatBehavior)} 注入。
 */
public interface MessengerCatBehavior {

    // ========== 目标与礼物 ==========

    // 送礼目标玩家 UUID，null 表示无固定目标（阶段机将直接进入消散）
    @Nullable
    UUID getTargetUuid();

    // 引来本次信使的羁绊猫 UUID；失效不会中断配送。
    @Nullable
    UUID getGuideUuid();

    // 礼物战利品表，null 表示不赠礼
    @Nullable
    ResourceKey<LootTable> getLootTable();

    // ========== 阶段时长（tick） ==========

    // 显现阶段持续时长
    int getManifestDuration();

    // 致意阶段持续时长（仅在 {@link #shouldGreet()} 返回 true 时进入）
    int getGreetDuration();

    // 消散阶段持续时长
    int getDissipateDuration();

    // 重新定位淡出/淡入阶段总时长。
    int getRelocateDuration();

    // 成功投放后的告别停留时长。
    int getFarewellDuration();

    // 幽灵猫总寿命上限，超时强制进入消散
    int getMaxLifetime();

    // ========== 阶段流转开关 ==========

    // 是否在抵达后进入致意阶段
    boolean shouldGreet();

    // 是否在致意后进入赠礼阶段（false 则直接消散，用于纯守护场景）
    boolean shouldDeliverGift();

    // ========== 阶段副作用（由阶段机在对应阶段调用） ==========

    // 显现阶段首 tick 触发（粒子汇聚、显现声效等）
    void onManifestStart(MessengerCat cat, ServerLevel level);

    // 接近阶段每 tick 触发（拖尾粒子等）
    void onApproachTick(MessengerCat cat, ServerLevel level);

    // 致意阶段每 tick 触发（心型粒子、轻柔猫叫等）
    void onGreetTick(MessengerCat cat, ServerLevel level, int ticksInPhase);

    // 赠礼阶段触发，返回是否实际生成了至少一件礼物。
    boolean onDeliver(MessengerCat cat, ServerLevel level);

    // 告别阶段首 tick 触发。
    void onFarewellStart(MessengerCat cat, ServerLevel level);

    // 消散阶段首 tick 触发（粒子爆散、远去声效等）
    void onDissipateStart(MessengerCat cat, ServerLevel level);
}
