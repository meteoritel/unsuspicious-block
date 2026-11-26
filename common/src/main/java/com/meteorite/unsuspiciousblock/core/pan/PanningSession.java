package com.meteorite.unsuspiciousblock.core.pan;

import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.item.PanItem;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.PanningStatePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** 一轮服务端淘洗，绑定起手时的点、手、工具与持续时间。 */
public record PanningSession(ShimmerEntity target, InteractionHand hand, ItemStack tool,
                             long startedAt, int duration) {
    /** 读取玩家当前的服务端会话。 */
    public static @Nullable PanningSession of(ServerPlayer player) {
        return ((PanningSessionHolder) player).unsuspiciousblock$getPanningSession();
    }

    /** 起手接入原版使用状态，并在使用被实际接受后通知观察者。 */
    public static boolean start(ServerPlayer player, ShimmerEntity target, InteractionHand hand, ItemStack tool) {
        stop(player);
        PanningSession session = new PanningSession(target, hand, tool, player.level().getGameTime(),
                Services.PANNING_CONFIG.getPanDurationTicks());
        ((PanningSessionHolder) player).unsuspiciousblock$setPanningSession(session);
        player.startUsingItem(hand);
        int acceptedDuration = player.getUseItemRemainingTicks();
        if (!player.isUsingItem() || acceptedDuration <= 0) {
            ((PanningSessionHolder) player).unsuspiciousblock$setPanningSession(null);
            return false;
        }
        if (acceptedDuration != session.duration) {
            session = new PanningSession(target, hand, tool, session.startedAt, acceptedDuration);
            ((PanningSessionHolder) player).unsuspiciousblock$setPanningSession(session);
        }
        target.startPanning(player);
        Services.NETWORK.broadcastPanningState(player, session.snapshot(player));
        return true;
    }

    /** 验证原操作仍指向起手目标，由服务端使用刻和结算共同调用。 */
    public boolean matches(ServerPlayer player, ItemStack stack) {
        return stack == this.tool && player.getUsedItemHand() == this.hand && player.level() == this.target.level()
                && this.target.canPanOnServer() && PanItem.findTargetedShimmer(player) == this.target;
    }

    /** 原版停止使用、换维度或失效时同步释放点位演出。 */
    public static void stop(ServerPlayer player) {
        PanningSession session = of(player);
        if (session == null) {
            return;
        }
        ((PanningSessionHolder) player).unsuspiciousblock$setPanningSession(null);
        session.target.stopPanning(player);
        Services.NETWORK.broadcastPanningState(player, PanningStatePayload.stopped(player.getUUID()));
    }

    /** 向新观察者补发仍在进行的会话。 */
    public static void sendToObserver(ServerPlayer subject, ServerPlayer observer) {
        PanningSession session = of(subject);
        if (session != null && subject.isUsingItem()) {
            Services.NETWORK.sendPanningState(observer, session.snapshot(subject));
        }
    }

    private PanningStatePayload snapshot(ServerPlayer player) {
        return new PanningStatePayload(player.getUUID(), this.target.getVariant().glow().medium(),
                this.hand, this.startedAt, this.duration);
    }
}
