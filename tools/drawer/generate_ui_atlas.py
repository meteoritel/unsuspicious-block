"""组装全模组 UI 图集各来源模块，输出统一纹理 (textures/gui/ui_atlas.png, 256x128 RGBA)。"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

from atlas import ATLAS_SOURCES
from atlas.palette import ATLAS_H, ATLAS_W, ZONE1_CELL_SIZE, render_source_sheet
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


def _export_contact_sheet(path: Path) -> int:
    result = render()
    zone1 = sorted(
        (r for r in result.regions if r.width == ZONE1_CELL_SIZE and r.height == ZONE1_CELL_SIZE),
        key=lambda r: (r.y, r.x),
    )
    sheet = render_source_sheet(zone1, result.image, bg='#F3E7C8', scale=6, cols=12, show_coords=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path)
    print(f'[对照图] ui_atlas Zone 1: {len(zone1)} icons -> {path}')
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--contact-sheet', type=Path, default=None)
    known, rest = parser.parse_known_args(argv)
    if known.contact_sheet is not None and not rest:
        return _export_contact_sheet(known.contact_sheet)
    status = run_texture_cli(SPEC, rest if known.contact_sheet is not None else argv)
    if status == 0 and known.contact_sheet is not None:
        return _export_contact_sheet(known.contact_sheet)
    return status


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
