package com.meteorite.unsuspiciousblock.platform.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.plugin.jade.JadeServerProviders;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade 发现入口；公共数据和客户端展示均由各自发现回调注册。 */
@WailaPlugin(Constants.MOD_ID)
public final class JadePlugin implements IWailaPlugin {
    /** 注册跨端共用的数据提供者。 */
    @Override
    public void register(IWailaCommonRegistration registration) {
        JadeServerProviders.register(registration);
    }

}
