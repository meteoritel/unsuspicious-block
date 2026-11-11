"""通用最近邻放大预览图生成器（棋盘格透明背景、区块标注与暗底可见性对比条）。"""
from __future__ import annotations

from PIL import Image, ImageDraw

from .atlas import RenderResult


def render_preview(
    result: RenderResult,
    *,
    scale: int = 4,
    dark_strip_prefixes: tuple[str, ...] = (),
) -> Image.Image:
    """将生成贴图渲染为审校用预览图，便于目视检查透明边缘与暗底对比度。"""
    source = result.image
    w, h = source.width * scale, source.height * scale
    dark_checks = (
        [region for region in result.regions if region.name.startswith(dark_strip_prefixes)]
        if dark_strip_prefixes
        else []
    )
    extra_h = 160 if dark_checks else 0

    preview = Image.new('RGBA', (w, h + extra_h), (236, 224, 194, 255))
    draw = ImageDraw.Draw(preview)

    tile = max(scale * 2, 4)
    for cy in range(0, h, tile):
        for cx in range(0, w, tile):
            c = (224, 210, 178, 255) if ((cx // tile) + (cy // tile)) % 2 == 0 else (238, 226, 196, 255)
            draw.rectangle((cx, cy, min(cx + tile - 1, w - 1), min(cy + tile - 1, h - 1)), fill=c)

    up = source.resize((w, h), Image.Resampling.NEAREST)
    preview.paste(up, (0, 0), up)

    for region in result.regions:
        outline = (180, 60, 60, 110) if region.width == 10 else (40, 110, 180, 110)
        draw.rectangle(
            (
                region.x * scale,
                region.y * scale,
                (region.x + region.width) * scale - 1,
                (region.y + region.height) * scale - 1,
            ),
            outline=outline,
        )

    if dark_checks:
        dark_strip_y = h + 12
        draw.rectangle((0, dark_strip_y, w, h + 150), fill=(46, 48, 54, 255))
        dx = 16
        for region in dark_checks:
            crop = source.crop((region.x, region.y, region.x + region.width, region.y + region.height))
            zoomed = crop.resize((region.width * scale, region.height * scale), Image.Resampling.NEAREST)
            preview.paste(zoomed, (dx, dark_strip_y + 18), zoomed)
            label = region.name.replace('DARK_', '').replace('POTTERY_', 'PW_')
            draw.text((dx, dark_strip_y + 92), label, fill=(230, 230, 230, 255))
            dx += region.width * scale + 34

    return preview
