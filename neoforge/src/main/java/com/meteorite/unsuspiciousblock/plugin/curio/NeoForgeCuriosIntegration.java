package com.meteorite.unsuspiciousblock.plugin.curio;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.OptionalModIntegration;
import com.meteorite.unsuspiciousblock.platform.Services;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * 仅在 Curios 已安装时加载的 NeoForge 注册入口。
 */
public final class NeoForgeCuriosIntegration implements Runnable {
    private static final String ARTIFACTS_PROVIDER =
            "com.meteorite.unsuspiciousblock.plugin.artifacts.ArtifactsSpecimenBoxSlotProvider";

    public NeoForgeCuriosIntegration() {
    }

    @Override
    public void run() {
        SpecimenBoxCurio.Lifecycle lifecycle = SpecimenBoxCurio.Lifecycle.NONE;
        if (Services.PLATFORM.isModLoaded("artifacts")) {
            lifecycle = OptionalModIntegration.invokeFactory(
                    ARTIFACTS_PROVIDER, "register", SpecimenBoxCurio.Lifecycle.class);
        }
        CuriosApi.registerCurio(ModItems.SPECIMEN_BOX, new SpecimenBoxCurio(lifecycle));
    }
}
