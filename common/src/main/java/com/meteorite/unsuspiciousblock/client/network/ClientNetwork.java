package com.meteorite.unsuspiciousblock.client.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;
import java.util.function.Consumer;

/** 客户端发送入口，由物理客户端的 Loader Adapter 安装发送能力。 */
public final class ClientNetwork {
    private static Consumer<CustomPacketPayload> sender;

    private ClientNetwork() {
    }

    /** 在客户端初始化时安装当前 Loader 的发送实现。 */
    public static void install(Consumer<CustomPacketPayload> packetSender) {
        sender = Objects.requireNonNull(packetSender);
    }

    /** 向当前连接的服务端发送请求。 */
    public static void sendToServer(CustomPacketPayload payload) {
        if (sender == null) {
            throw new IllegalStateException("客户端网络尚未安装");
        }
        sender.accept(payload);
    }
}
