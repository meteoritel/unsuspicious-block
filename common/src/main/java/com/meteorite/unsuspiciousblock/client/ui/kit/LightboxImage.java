package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 灯箱要展示的一张图：稳定 ID、贴图区域（原始宽高显式给出）与可本地化标题/描述。
 *
 * <p>只接受**已在客户端可用**的贴图来源：不解析 URL、不下载网络图片、不读任意文件路径。
 * 区域必须落在贴图范围内，非法尺寸在构造时就被拒绝，避免把错误推迟到绘制阶段。</p>
 *
 * @param id            宿主侧稳定 ID（同一贴图的不同区域也要能区分），供图集记忆与调试读数使用
 * @param texture       贴图 ResourceLocation
 * @param u             区域左上角 U（贴图像素）
 * @param v             区域左上角 V（贴图像素）
 * @param width         区域宽度（也即图片的原始像素宽）
 * @param height        区域高度（也即图片的原始像素高）
 * @param textureWidth  贴图总宽
 * @param textureHeight 贴图总高
 * @param title         可本地化标题；{@code null} 表示不显示
 * @param description   可本地化替代描述（图片本身没有 OCR，读屏信息只能由调用方给出）；{@code null} 表示不显示
 */
public record LightboxImage(ResourceLocation id, ResourceLocation texture, int u, int v,
                            int width, int height, int textureWidth, int textureHeight,
                            @Nullable Component title, @Nullable Component description) {
    public LightboxImage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(texture);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Non-positive image size");
        if (u < 0 || v < 0 || textureWidth <= 0 || textureHeight <= 0
                || (long) u + width > textureWidth || (long) v + height > textureHeight) {
            throw new IllegalArgumentException("Invalid image region");
        }
        if (title != null) title = title.copy();
        if (description != null) description = description.copy();
    }

    /** 整张贴图作为一张图：区域等于贴图自身尺寸。 */
    public static LightboxImage whole(ResourceLocation id, ResourceLocation texture, int width, int height,
                                      @Nullable Component title, @Nullable Component description) {
        return new LightboxImage(id, texture, 0, 0, width, height, width, height, title, description);
    }
}
