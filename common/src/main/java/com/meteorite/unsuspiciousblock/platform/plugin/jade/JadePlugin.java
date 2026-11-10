package com.meteorite.unsuspiciousblock.platform.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.plugin.jade.JadeServerProviders;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

import java.util.Objects;
import java.util.function.Consumer;

/** Jade 发现入口；公共数据直接注册，展示回调由客户端初始化安装。 */
@WailaPlugin(Constants.MOD_ID)
public final class JadePlugin implements IWailaPlugin {
    private static volatile Consumer<IWailaClientRegistration> clientRegistration;

    /** 在 Jade 插件发现前由物理客户端安装展示回调。 */
    public static void installClient(Consumer<IWailaClientRegistration> registration) {
        clientRegistration = Objects.requireNonNull(registration);
    }

    /** 注册跨端共用的数据提供者。 */
    @Override
    public void register(IWailaCommonRegistration registration) {
        JadeServerProviders.register(registration);
    }

    /** Jade 仅在物理客户端调用此入口，客户端能力已在初始化阶段安装。 */
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        Consumer<IWailaClientRegistration> client = clientRegistration;
        if (client == null) {
            throw new IllegalStateException("Jade 客户端展示尚未安装");
        }
        client.accept(registration);
    }
}
