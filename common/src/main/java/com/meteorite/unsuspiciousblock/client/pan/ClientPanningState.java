package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.core.item.PanItem;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.PanningStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 保存当前客户端世界中服务端确认的淘洗表现，供物品属性和手臂渲染读取。 */
public final class ClientPanningState {
    private static final Map<UUID, PanningStatePayload> ACTIVE = new HashMap<>();
    private static ClientLevel level;

    private ClientPanningState() {
    }

    /** 处理玩家的开始或结束快照，允许快照先于新追踪实体到达。 */
    public static void receive(PanningStatePayload payload) {
        Minecraft client = Minecraft.getInstance();
        updateLevel(client.level);
        if (payload.duration() <= 0) {
            ACTIVE.remove(payload.playerId());
            LocalPlayer player = client.player;
            if (player != null && player.getUUID().equals(payload.playerId())
                    && player.getUseItem().getItem() instanceof PanItem) {
                player.stopUsingItem();
            }
        } else {
            ACTIVE.put(payload.playerId(), payload);
        }
    }

    /** 丢弃已经离开追踪范围且未收到结束快照的过时演出。 */
    public static void tick() {
        ClientLevel world = Minecraft.getInstance().level;
        updateLevel(world);
        if (world != null && !ACTIVE.isEmpty()) {
            long now = world.getGameTime();
            ACTIVE.values().removeIf(state -> now > state.startedAt() + state.duration());
        }
    }

    private static void updateLevel(@Nullable ClientLevel world) {
        if (world != level) {
            ACTIVE.clear();
            level = world;
        }
    }

    /** 返回玩家当前世界的演出时钟。 */
    public static @Nullable PanningStatePayload of(LivingEntity entity) {
        return entity instanceof Player && entity.level() == level ? ACTIVE.get(entity.getUUID()) : null;
    }

    /** 断开连接时释放当前世界与淘洗快照。 */
    public static void reset() {
        updateLevel(null);
    }
}
