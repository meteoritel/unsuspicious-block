package com.meteorite.unsuspiciousblock.client.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade 客户端回调入口；构造和公共注册阶段不访问客户端实现。 */
@WailaPlugin(Constants.MOD_ID)
public final class JadeClientPlugin implements IWailaPlugin {
    /** 由 Jade 在物理客户端调用，注册展示 provider。 */
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        JadeClientProviders.register(registration);
    }
}
