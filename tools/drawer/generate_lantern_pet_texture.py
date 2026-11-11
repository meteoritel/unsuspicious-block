"""生成灵魂提灯宠物实体贴图 (textures/entity/soul_lantern_pet.png, 64x64 RGBA)。"""
from __future__ import annotations

from drawlib import AtlasBuilder, PixelCanvas, RenderResult, TextureSpec, run_texture_cli

# 金属部件（深棕渐变）
METAL_LIGHT = '#6A543C'
METAL_MID = '#4A3A2A'
METAL_DARK = '#2A1F1A'
METAL_EDGE = '#1E1612'

# 玻璃面（深蓝紫色）
GLASS_LIGHT = '#283C64'
GLASS_BASE = '#1A2A4A'
GLASS_EDGE = '#141E37'

# 灵魂火苗（青蓝色）
FLAME_CORE = '#5AE0FF'
FLAME_MID = '#3CAAFF'
FLAME_EDGE = '#2A8AFF'

# 底坠（暗灰金属）
TASSEL_LIGHT = '#4E4641'
TASSEL_DARK = '#322D2A'


def _gradient_box(top: str, bottom: str, edge: str):
    def _paint(c: PixelCanvas) -> None:
        c.fill_gradient_v(0, 0, c.width, c.height, top, bottom)
        c.stroke_rect(0, 0, c.width, c.height, edge)

    return _paint


def _paint_flame(c: PixelCanvas) -> None:
    for x in range(c.width):
        dist = abs(x - 5)
        color = FLAME_CORE if dist <= 2 else FLAME_MID if dist <= 4 else FLAME_EDGE
        c.vline(x, 0, c.height, color)


def render() -> RenderResult:
    """按 LanternPetModel UV 布局绘制灵魂提灯宠物贴图。"""
    atlas = AtlasBuilder(64, 64)
    atlas.paint('ring', 0, 0, 24, 7, _gradient_box(METAL_LIGHT, METAL_DARK, METAL_EDGE))
    atlas.paint('chain', 28, 0, 4, 3, lambda c: c.stroke_rect(0, 0, c.width, c.height, METAL_EDGE, fill=METAL_MID))
    atlas.paint('cap_top', 0, 8, 28, 8, _gradient_box(METAL_MID, METAL_DARK, METAL_EDGE))

    for name, u in (('post_nw', 0), ('post_ne', 5), ('post_sw', 10), ('post_se', 15)):
        atlas.paint(name, u, 17, 4, 9, _gradient_box(METAL_MID, METAL_DARK, METAL_EDGE))

    for name, u in (('glass_n', 0), ('glass_s', 13), ('glass_e', 26), ('glass_w', 39)):
        atlas.paint(name, u, 27, 12, 8, _gradient_box(GLASS_LIGHT, GLASS_BASE, GLASS_EDGE))

    atlas.paint('flame', 0, 36, 12, 8, _paint_flame)
    atlas.paint('cap_bottom', 0, 45, 28, 8, _gradient_box(METAL_DARK, METAL_MID, METAL_EDGE))
    atlas.paint('tassel', 30, 45, 8, 5, _gradient_box(TASSEL_LIGHT, TASSEL_DARK, METAL_EDGE))
    return atlas.build()


SPEC = TextureSpec(
    name='lantern_pet',
    description=__doc__ or '生成灵魂提灯宠物实体贴图',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/entity/soul_lantern_pet.png',
    render=render,
)

if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
