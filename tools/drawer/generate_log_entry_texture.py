"""生成考古日志条目背景纹理精灵图集 (textures/gui/log_entry.png, 148x78 RGBA)。"""
from __future__ import annotations

from drawlib import AtlasBuilder, RenderResult, TextureSpec, run_texture_cli

WIDTH = 148
STATE_HEIGHT = 26
CORNER_RADIUS = 3
BORDER_WIDTH = 1

# 状态配色 (RGBA) - 羊皮纸融合方案
STATES_CONFIG = (
    ('normal', (195, 175, 145, 45), (165, 145, 120, 80)),
    ('hover', (215, 185, 150, 90), (140, 110, 80, 140)),
    ('selected', (165, 125, 90, 80), (190, 145, 60, 240)),
)


def render() -> RenderResult:
    """绘制三态圆角日志条目底纹条带。"""
    atlas = AtlasBuilder(WIDTH, STATE_HEIGHT * len(STATES_CONFIG))
    for index, (state, fill, border) in enumerate(STATES_CONFIG):
        atlas.paint(
            state,
            0,
            index * STATE_HEIGHT,
            WIDTH,
            STATE_HEIGHT,
            lambda c, f=fill, b=border: c.rounded_rect(
                0, 0, c.width, c.height, CORNER_RADIUS, fill=f, border=b, border_width=BORDER_WIDTH
            ),
        )
    return atlas.build()


SPEC = TextureSpec(
    name='log_entry',
    description=__doc__ or '生成考古日志条目背景纹理',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/gui/log_entry.png',
    render=render,
)

if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
