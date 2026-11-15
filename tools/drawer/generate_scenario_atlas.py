"""生成场景页专用底板图集 (textures/gui/scenario_panels.png, 80x16 RGBA)。"""
from __future__ import annotations

from drawlib import AtlasBuilder, RenderResult, TextureSpec, run_texture_cli

WIDTH = 80
HEIGHT = 16


def _panel(fill: tuple[int, int, int, int], border: tuple[int, int, int, int],
           highlight: tuple[int, int, int, int], shadow: tuple[int, int, int, int]):
    def paint(canvas):
        canvas.bevel_rect(0, 0, 16, 16, border=border, fill=fill,
                          top_left=highlight, bottom=shadow, right=shadow)
    return paint


def render() -> RenderResult:
    """集中存放场景页、浮层和条件卡片的 9-slice 底板。"""
    atlas = AtlasBuilder(WIDTH, HEIGHT)
    atlas.paint('SCENARIO_PANEL', 0, 0, 16, 16,
                _panel((242, 229, 198, 255), (180, 155, 115, 255),
                       (255, 244, 217, 255), (223, 204, 164, 255)))
    atlas.paint('SCENARIO_SECTION', 16, 0, 16, 16,
                _panel((139, 105, 20, 38), (139, 105, 20, 92),
                       (255, 255, 255, 34), (0, 0, 0, 28)))
    atlas.paint('SCENARIO_CARD', 32, 0, 16, 16,
                _panel((107, 125, 70, 38), (107, 125, 70, 128),
                       (226, 242, 198, 84), (38, 63, 37, 68)))
    atlas.paint('SCENARIO_CARD_HOVER', 48, 0, 16, 16,
                _panel((107, 125, 70, 76), (107, 125, 70, 176),
                       (239, 252, 211, 112), (38, 63, 37, 92)))
    atlas.paint('SCENARIO_SELECTED', 64, 0, 16, 16,
                _panel((123, 62, 24, 92), (123, 62, 24, 196),
                       (255, 214, 144, 120), (73, 30, 18, 96)))
    return atlas.build()


SPEC = TextureSpec(
    name='scenario_atlas',
    description=__doc__ or '生成场景页专用底板图集',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/gui/scenario_panels.png',
    render=render,
)


if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
