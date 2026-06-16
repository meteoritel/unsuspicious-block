package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorStateHolder;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 猫之恩惠核心管理器——统一处理恩惠值的累积、惩罚、冷却与客户端同步。
 * 所有累积/惩罚逻辑均为服务端权威，变更后通过 S2C 包同步给客户端供 tooltip 显示。
 */
public final class CatFavorManager {
    // 玩家每次死亡的恩惠惩罚（无冷却）
    private static final int DEATH_PENALTY = 10;
    // 玩家每次杀死猫的恩惠惩罚（无冷却）
    private static final int KILL_CAT_PENALTY = 50;

    private CatFavorManager() {
    }

    // 初始化：注入平台适配器并注册事件监听
    public static void init(ICatEventAdapter adapter) {
        adapter.registerListeners();
        Constants.LOG.info("CatFavorManager 已初始化，适配器: {}", adapter.getClass().getSimpleName());
    }

    // ========== 累积行为 ==========

    /**
     * 尝试为玩家累积一次指定行为的恩惠值。
     * 仅当玩家背包中存在「猫之手」且该行为已过冷却时生效。
     */
    public static void tryAccumulate(ServerPlayer player, CatFavorAction action) {
        if (player == null || !hasHandOfCatInInventory(player)) {
            return;
        }
        CatFavorState state = getState(player);
        if (state == null) {
            return;
        }
        long gameTime = player.serverLevel().getGameTime();
        if (!state.canTrigger(action, gameTime)) {
            return;
        }
        boolean changed = state.addFavor(action.favorDelta());
        state.markTriggered(action, gameTime);
        if (changed) {
            sync(player);
        }
    }

    // ========== 惩罚行为（无冷却，不要求持有猫之手） ==========

    // 玩家死亡：恩惠 -10
    public static void onPlayerDeath(ServerPlayer player) {
        applyPenalty(player, DEATH_PENALTY);
    }

    // 玩家杀死猫：恩惠 -50
    public static void onKillCat(ServerPlayer player) {
        applyPenalty(player, KILL_CAT_PENALTY);
    }

    private static void applyPenalty(ServerPlayer player, int penalty) {
        if (player == null) {
            return;
        }
        CatFavorState state = getState(player);
        if (state == null) {
            return;
        }
        if (state.addFavor(-penalty)) {
            sync(player);
        }
    }

    // ========== 查询与同步 ==========

    // 获取玩家当前恩惠值（无状态时返回 0）
    public static int getFavor(ServerPlayer player) {
        CatFavorState state = getState(player);
        return state == null ? 0 : state.getFavor();
    }

    // 向客户端同步当前恩惠值，供物品 tooltip 显示
    public static void sync(ServerPlayer player) {
        Services.NETWORK.sendToPlayer(player, new SyncCatFavorPayload(getFavor(player)));
    }

    // 从玩家实例获取猫之恩惠状态（通过 mixin 持有者接口）
    public static CatFavorState getState(Player player) {
        if (player instanceof CatFavorStateHolder holder) {
            return holder.unsuspiciousblock$getCatFavorState();
        }
        return null;
    }

    // 检查玩家背包中是否存在「猫之手」
    public static boolean hasHandOfCatInInventory(Player player) {
        if (ModItems.HAND_OF_CAT == null) {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == ModItems.HAND_OF_CAT) {
                return true;
            }
        }
        return false;
    }
}
