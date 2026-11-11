package com.meteorite.unsuspiciousblock.core.cat;

import net.minecraft.network.chat.Component;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorView;
import com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger;
import com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger.Event;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorStateHolder;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatFavorPayload;
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

    /** 绑定和关系建立先于被动能力；喂食在 tick 末统一结算。 */
    public static void serverTick(ServerPlayer player) {
        if (getState(player) == null) return;
        boolean bound = HandOfCatBinding.bindFirstIfNeeded(player);
        if (bound || HandOfCatBinding.hasOwned(player)) apply(player, Event.ESTABLISH_RELATIONSHIP);
        apply(player, Event.SETTLE_FEED);
    }

    public static void queueFeedReward(ServerPlayer player) {
        apply(player, Event.QUEUE_FEED);
    }

    public static void cancelPendingFeedReward(ServerPlayer player) {
        apply(player, Event.CANCEL_FEED);
    }

    /** 正向行为受关系与冷却约束，惩罚使用带目标的入口。 */
    public static void tryAccumulate(ServerPlayer player, CatFavorAction action) {
        if (player == null || action.isPenalty()) return;
        apply(player, new CatBondLedger.Behavior(action, null));
    }

    public static void onHitCat(ServerPlayer player, Cat cat) {
        apply(player, new CatBondLedger.Behavior(CatFavorAction.HIT_CAT, cat.getUUID()));
    }

    public static void onOwnCatDeath(ServerPlayer player) {
        apply(player, new CatBondLedger.Behavior(CatFavorAction.OWN_CAT_DEATH, null));
    }

    public static void onKillCat(ServerPlayer player, Cat cat) {
        apply(player, new CatBondLedger.Behavior(CatFavorAction.KILL_CAT, cat.getUUID()));
    }

    /** 玩家侧唯一玩法写入出口，消息与状态同步由账本的实际变更派生。 */
    public static boolean apply(ServerPlayer player, CatBondLedger.Mutation mutation) {
        CatFavorState state = getState(player);
        if (state == null) return false;
        CatBondLedger.Change change = CatBondLedger.apply(state, mutation, player.serverLevel().getGameTime());
        if (change.delta() != 0 && (mutation instanceof CatBondLedger.Behavior || mutation == Event.SETTLE_FEED)) {
            showBondChange(player, change.delta(), change.bond());
        }
        if (change.requiresSync()) sync(player);
        return change.applied();
    }

    private static void showBondChange(ServerPlayer player, int delta, int currentBond) {
        String signedDelta = delta > 0 ? "+" + delta : Integer.toString(delta);
        player.displayClientMessage(Component.translatable(
                "message.unsuspiciousblock.cat_bond.changed", signedDelta, currentBond), true);
    }

    // ========== 查询与同步 ==========

    public static int getCatBond(ServerPlayer player) {
        CatFavorState state = getState(player);
        return state == null ? 0 : state.getCatBond();
    }

    // 获取玩家当前九命命数（无状态时返回 0）
    public static int getNineLivesCount(ServerPlayer player) {
        CatFavorState state = getState(player);
        return state == null ? 0 : state.getNineLivesCount();
    }

    /** 满羁绊时信使补充命数，上限由账本控制。 */
    public static void onMessengerGiftDelivered(ServerPlayer player) {
        apply(player, Event.MESSENGER_GIFT);
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

    /** 玩法读方只消费视图；getState 用于灵体瞬态 UUID 接线与记账。 */
    public static CatFavorView view(Player player) {
        return getState(player);
    }

    public static boolean hasOwnedHandOfCat(Player player) {
        return HandOfCatBinding.hasOwned(player);
    }

    public static boolean hasHandOfCatInInventory(Player player) {
        return HandOfCatBinding.hasOwned(player);
    }
}
