package com.meteorite.unsuspiciousblock.cat.state;

/**
 * 猫之恩惠状态持有者接口——通过 mixin 附加到 Player。
 * 用于从玩家实例中获取 CatFavorState。
 */
public interface CatFavorStateHolder {
    // 获取玩家的猫之恩惠状态实例
    CatFavorState unsuspiciousblock$getCatFavorState();
}
