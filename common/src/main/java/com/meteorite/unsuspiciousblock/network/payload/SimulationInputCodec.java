package com.meteorite.unsuspiciousblock.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** 模拟协议的集合长度上限校验。 */
public final class SimulationInputCodec {
    private SimulationInputCodec() {}

    public static int count(RegistryFriendlyByteBuf buf, int max) {
        int value = buf.readVarInt();
        if (value < 0 || value > max) throw new IllegalArgumentException("非法集合长度");
        return value;
    }
}

