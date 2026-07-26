package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorStateHolder;
import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.HandOfCatItem;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;

/**
 * 猫之恩惠核心管理器——统一处理恩惠值的累积、惩罚、冷却与客户端同步。
 * 所有累积/惩罚逻辑均为服务端权威，变更后通过 S2C 包同步给客户端供 tooltip 显示。
 */
public final class CatFavorManager {

    private CatFavorManager() {
    }

    // 初始化：注入平台适配器并注册事件监听
    public static void init(ICatEventAdapter adapter) {
        adapter.registerListeners();
        Constants.LOG.info("CatFavorManager 已初始化，适配器: {}", adapter.getClass().getSimpleName());
    }

    // 服务端每 tick 处理信物绑定与关系建立，必须先于被动能力评估执行。
    public static void serverTick(ServerPlayer player) {
        CatFavorState state = getState(player);
        if (state == null || ModItems.HAND_OF_CAT == null) {
            return;
        }
        if (hasOwnedHandOfCat(player)) {
            if (state.establishRelationship()) {
                sync(player);
            }
        } else {
            boolean bound = InventoryPresenceRegistry.mutateFirst(player,
                    stack -> stack.getItem() == ModItems.HAND_OF_CAT && !HandOfCatItem.isBound(stack),
                    stack -> HandOfCatItem.bindTo(stack, player));
            if (bound) {
                state.establishRelationship();
                sync(player);
            }
        }
        if (state.consumePendingFeedReward()) {
            tryAccumulate(player, CatFavorAction.FEED_CAT);
        }
    }

    // 将有效喂食延迟到 tick 末，给成功驯服事件留下去重机会。
    public static void queueFeedReward(ServerPlayer player) {
        CatFavorState state = getState(player);
        if (state != null) {
            state.queueFeedReward();
        }
    }

    // 成功驯服猫时取消同次交互的喂食奖励。
    public static void cancelPendingFeedReward(ServerPlayer player) {
        CatFavorState state = getState(player);
        if (state != null) {
            state.cancelPendingFeedReward();
        }
    }

    // ========== 累积行为 ==========

    /**
     * 尝试为玩家结算一次正向猫族羁绊行为。
     * 关系建立后不要求继续携带猫之手，行为仍受独立冷却约束。
     */
    public static void tryAccumulate(ServerPlayer player, CatFavorAction action) {
        if (player == null || action.isPenalty()) {
            return;
        }
        CatFavorState state = getState(player);
        if (state == null || !state.isRelationshipEstablished()) {
            return;
        }
        long gameTime = player.serverLevel().getGameTime();
        if (!state.canTrigger(action, gameTime)) {
            return;
        }
        boolean changed = state.addCatBond(action.favorDelta());
        state.markTriggered(action, gameTime);
        if (changed) {
            showBondChange(player, action.favorDelta(), state.getCatBond());
            sync(player);
        }
    }

    // ========== 惩罚行为（无冷却，不要求持有猫之手） ==========

    // 击打猫：恩惠 -5
    public static void onHitCat(ServerPlayer player, Cat cat) {
        CatFavorState state = getState(player);
        if (state == null || !state.isRelationshipEstablished()) {
            return;
        }
        state.recordCatHit(cat.getUUID(), player.serverLevel().getGameTime());
        applyPenalty(player, CatFavorAction.HIT_CAT.favorDelta());
    }

    // 玩家所属的驯服猫死亡：恩惠 -10
    public static void onOwnCatDeath(ServerPlayer player) {
        applyPenalty(player, CatFavorAction.OWN_CAT_DEATH.favorDelta());
    }

    // 玩家杀死猫：恩惠 -50
    public static void onKillCat(ServerPlayer player, Cat cat) {
        CatFavorState state = getState(player);
        if (state == null || !state.isRelationshipEstablished()) {
            return;
        }
        boolean hitAlreadyApplied = state.consumeMatchingCatHit(
                cat.getUUID(), player.serverLevel().getGameTime());
        int delta = CatFavorAction.KILL_CAT.favorDelta()
                - (hitAlreadyApplied ? CatFavorAction.HIT_CAT.favorDelta() : 0);
        applyPenalty(player, delta);
    }

    // 统一应用惩罚：直接扣减恩惠（不校验持有物与冷却）
    private static void applyPenalty(ServerPlayer player, int delta) {
        if (player == null || delta >= 0) {
            return;
        }
        CatFavorState state = getState(player);
        if (state == null || !state.isRelationshipEstablished()) {
            return;
        }
        if (state.addCatBond(delta)) {
            showBondChange(player, delta, state.getCatBond());
            sync(player);
        }
    }

    private static void showBondChange(ServerPlayer player, int delta, int currentBond) {
        String signedDelta = delta > 0 ? "+" + delta : Integer.toString(delta);
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.unsuspiciousblock.cat_bond.changed", signedDelta, currentBond), true);
    }

    // ========== 查询与同步 ==========

    // 获取玩家当前恩惠值（无状态时返回 0）
    public static int getFavor(ServerPlayer player) {
        CatFavorState state = getState(player);
        return state == null ? 0 : state.getFavor();
    }

    public static int getCatBond(ServerPlayer player) {
        CatFavorState state = getState(player);
        return state == null ? 0 : state.getCatBond();
    }

    // 获取玩家当前九命命数（无状态时返回 0）
    public static int getNineLivesCount(ServerPlayer player) {
        CatFavorState state = getState(player);
        return state == null ? 0 : state.getNineLivesCount();
    }

    // 猫猫信使成功投放礼物时，仅在羁绊仍为 100 的情况下补充一条命。
    public static void onMessengerGiftDelivered(ServerPlayer player) {
        CatFavorState state = getState(player);
        if (state == null || state.getCatBond() < CatFavorState.MAX_FAVOR
                || state.getNineLivesCount() >= 9) {
            return;
        }
        state.addOneLife();
        sync(player);
    }

    // 向客户端同步当前恩惠值与命数，供物品 tooltip 与 HUD 显示
    public static void sync(ServerPlayer player) {
        CatFavorState state = getState(player);
        boolean relationshipEstablished = state != null && state.isRelationshipEstablished();
        Services.NETWORK.sendToPlayer(player, new SyncCatFavorPayload(
                getCatBond(player), getNineLivesCount(player), relationshipEstablished));
    }

    // 从玩家实例获取猫之恩惠状态（通过 mixin 持有者接口）
    public static CatFavorState getState(Player player) {
        if (player instanceof CatFavorStateHolder holder) {
            return holder.unsuspiciousblock$getCatFavorState();
        }
        return null;
    }

    // 检查个人携带范围内是否存在绑定给当前玩家的猫之手。
    public static boolean hasOwnedHandOfCat(Player player) {
        if (ModItems.HAND_OF_CAT == null) {
            return false;
        }
        return InventoryPresenceRegistry.containsMatching(player,
                stack -> stack.getItem() == ModItems.HAND_OF_CAT
                        && HandOfCatItem.isBoundTo(stack, player.getUUID()));
    }

    // 兼容现有能力调用；语义已收紧为“本人猫之手”。
    public static boolean hasHandOfCatInInventory(Player player) {
        return hasOwnedHandOfCat(player);
    }
}
