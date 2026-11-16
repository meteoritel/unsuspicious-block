package com.meteorite.unsuspiciousblock.platform.world;

import com.meteorite.unsuspiciousblock.core.world.aura.BlockAuras;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

/** Fabric 世界事件到公共光环生命周期的接入。 */
public final class FabricAuraEvents {
    private FabricAuraEvents() {
    }

    /** 在模组初始化时绑定区块与世界装卸。 */
    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(BlockAuras::loadChunk);
        ServerChunkEvents.CHUNK_UNLOAD.register(BlockAuras::unloadChunk);
        ServerWorldEvents.UNLOAD.register((server, level) -> BlockAuras.unloadLevel(level));
    }
}
