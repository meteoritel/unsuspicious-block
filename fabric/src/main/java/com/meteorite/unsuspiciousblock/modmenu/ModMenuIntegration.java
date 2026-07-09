package com.meteorite.unsuspiciousblock.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** ModMenu 联动入口--将本模组配置界面注册到 ModMenu 的「配置」按钮 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModMenuConfigScreen::new;
    }
}
