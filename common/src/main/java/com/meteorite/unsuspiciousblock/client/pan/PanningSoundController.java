package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.HashMap;
import java.util.Map;

/*** 附近淘洗点的水声管理：每五刻检查一次，每个工作点最多播放一个声音实例。 */
public final class PanningSoundController {
    private static final Map<ShimmerEntity, PanningSound> SOUNDS = new HashMap<>();
    private static ClientLevel soundLevel;
    private static int scanTicks;

    private PanningSoundController() {
    }

    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (soundLevel != client.level) {
            reset();
            soundLevel = client.level;
        }
        if (client.level == null || client.player == null || client.isPaused() || ++scanTicks % 5 != 0) {
            return;
        }
        SOUNDS.entrySet().removeIf(entry -> {
            if (entry.getValue().isStopped() || entry.getKey().isRemoved() || !entry.getKey().isPanning()
                    || !client.getSoundManager().isActive(entry.getValue())) {
                entry.getValue().finish();
                client.getSoundManager().stop(entry.getValue());
                return true;
            }
            return false;
        });
        for (ShimmerEntity shimmer : client.level.getEntitiesOfClass(ShimmerEntity.class,
                client.player.getBoundingBox().inflate(16.0D), ShimmerEntity::isPanning)) {
            if (!SOUNDS.containsKey(shimmer)) {
                PanningSound sound = new PanningSound(shimmer);
                SOUNDS.put(shimmer, sound);
                client.getSoundManager().play(sound);
            }
        }
    }

    public static void reset() {
        for (PanningSound sound : SOUNDS.values()) {
            sound.finish();
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
        SOUNDS.clear();
        soundLevel = null;
        scanTicks = 0;
    }
}
