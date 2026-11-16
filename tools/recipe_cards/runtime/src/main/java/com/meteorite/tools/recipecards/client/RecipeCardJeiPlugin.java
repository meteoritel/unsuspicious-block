package com.meteorite.tools.recipecards.client;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** 提供当前 JEI runtime，避免工具读取 JEI 内部静态状态。 */
@JeiPlugin
public final class RecipeCardJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RecipeCardTool.MOD_ID, "export");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime available) {
        runtime = available;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** 返回已就绪的 runtime；未进入世界或 JEI 重载期间明确失败。 */
    public static IJeiRuntime requireRuntime() {
        if (runtime == null) {
            throw new IllegalStateException("JEI 尚未就绪，请进入世界并等待配方加载完成");
        }
        return runtime;
    }
}
