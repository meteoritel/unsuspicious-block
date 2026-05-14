package com.meteorite.unsuspiciousblock.menu;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

/** 菜单类型引用——由平台模块分别注册并回写 */
public final class ModMenus {
    public static final ResourceLocation SPECIMEN_BOX_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "specimen_box");

    public static MenuType<SpecimenBoxMenu> SPECIMEN_BOX;

    private ModMenus() {
    }
}
