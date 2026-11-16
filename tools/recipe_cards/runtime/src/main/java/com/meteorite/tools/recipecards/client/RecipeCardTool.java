package com.meteorite.tools.recipecards.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** 工具专属客户端入口，将导出请求放在完整游戏帧之后执行。 */
@Mod(value = RecipeCardTool.MOD_ID, dist = Dist.CLIENT)
public final class RecipeCardTool {
    public static final String MOD_ID = "usb_recipe_cards";

    public RecipeCardTool() {
        NeoForge.EVENT_BUS.addListener(RecipeCardExports::afterFrame);
    }
}
