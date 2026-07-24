package com.meteorite.unsuspiciousblock.plugin.trinket;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.OptionalModIntegration;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 仅在 Trinkets 已安装时加载的 Fabric 注册入口。
 */
public final class FabricTrinketsIntegration implements Runnable {
    private static final String ARTIFACTS_PROVIDER =
            "com.meteorite.unsuspiciousblock.plugin.artifacts.ArtifactsSpecimenBoxSlotProvider";

    public FabricTrinketsIntegration() {
    }

    @Override
    public void run() {
        SpecimenBoxTrinket.Lifecycle lifecycle = SpecimenBoxTrinket.Lifecycle.NONE;
        if (FabricLoader.getInstance().isModLoaded("artifacts")) {
            lifecycle = OptionalModIntegration.invokeFactory(
                    ARTIFACTS_PROVIDER, "register", SpecimenBoxTrinket.Lifecycle.class);
        }
        TrinketsApi.registerTrinket(ModItems.SPECIMEN_BOX, new SpecimenBoxTrinket(lifecycle));
    }
}
