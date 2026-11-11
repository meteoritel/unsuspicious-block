"""组装全模组 UI 图集各来源模块，输出统一纹理 (textures/gui/ui_atlas.png, 256x128 RGBA)。"""
from __future__ import annotations

from atlas import ATLAS_SOURCES
from atlas.palette import ATLAS_H, ATLAS_W
from drawlib import AtlasBuilder, RenderResult, TextureSpec, run_texture_cli


def render() -> RenderResult:
    """按 UI 来源分包依次绘制并验证无重叠后输出总图集。"""
    atlas = AtlasBuilder(ATLAS_W, ATLAS_H)
    for _, _, applier in ATLAS_SOURCES:
        applier(atlas)
    return atlas.build()


SPEC = TextureSpec(
    name='ui_atlas',
    description=__doc__ or '生成全模组统一 UI 图标与控件皮肤图集',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/gui/ui_atlas.png',
    render=render,
    default_preview_scale=4,
    dark_strip_prefixes=('DARK_', 'POTTERY_', 'CAT_'),
)

if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
