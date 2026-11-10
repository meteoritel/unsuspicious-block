package com.meteorite.unsuspiciousblock.client.plugin.jade;

import com.meteorite.unsuspiciousblock.platform.plugin.jade.JadePlugin;

/** 由客户端在确认 Jade 已加载后通过反射创建的安装入口。 */
@SuppressWarnings("unused")
public final class JadeClientIntegration implements Runnable {
    /** 安装展示注册回调，Jade 发现入口保持独立于客户端实现。 */
    @Override
    public void run() {
        JadePlugin.installClient(JadeClientProviders::register);
    }
}
