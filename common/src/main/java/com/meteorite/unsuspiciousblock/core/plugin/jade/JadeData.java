package com.meteorite.unsuspiciousblock.core.plugin.jade;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;

/** Jade 双端共用的 provider 标识和数据字段。 */
public final class JadeData {
    public static final ResourceLocation SCANNED_LOOT_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "scanned_loot");
    public static final ResourceLocation SEALED_CONTENTS_ID =
            ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "sealed_contents");
    // 保留 Jade 配置中用于启用此 provider 的标识。
    public static final ResourceLocation SHIMMER_STATUS_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "shimmer_lifetime");

    public static final String TAG_SHIMMER_DEADLINE = "ShimmerDeadline";

    public static final String TAG_SEALED_ITEM = "SealedItem";
    public static final String TAG_CRAFTER_NAME = "CrafterName";


    private JadeData() {
    }
}
