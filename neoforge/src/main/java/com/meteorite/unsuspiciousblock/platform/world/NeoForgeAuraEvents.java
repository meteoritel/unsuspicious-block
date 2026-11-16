package com.meteorite.unsuspiciousblock.platform.world;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import com.meteorite.unsuspiciousblock.core.world.aura.BlockAuras;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** NeoForge 世界生命周期与攻击目标事件接入。 */
@EventBusSubscriber(modid = Constants.MOD_ID)
public final class NeoForgeAuraEvents {
    private NeoForgeAuraEvents() {
    }

    /** 恢复所有已加载服务端区块的光环源。 */
    @SubscribeEvent
    public static void loadChunk(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockAuras.loadChunk(level, event.getChunk());
        }
    }

    /** 区块卸载时释放源及跨区块覆盖。 */
    @SubscribeEvent
    public static void unloadChunk(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockAuras.unloadChunk(level, event.getChunk());
        }
    }

    /** 世界卸载时释放其瞬态场。 */
    @SubscribeEvent
    public static void unloadLevel(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockAuras.unloadLevel(level);
        }
    }

    /** 用空目标替换受保护目标，避免取消事件保留旧仇恨。 */
    @SubscribeEvent
    public static void selectTarget(LivingChangeTargetEvent event) {
        if (AuraRepulsion.isProtectedTarget(event.getEntity(), event.getNewAboutToBeSetTarget())) {
            event.setNewAboutToBeSetTarget(null);
        }
    }
}
