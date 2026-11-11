"""生成考古笔记书签标签三态纹理 (textures/gui/bookmark_tab.png, 40x66 RGBA)。"""
from __future__ import annotations

from drawlib import AtlasBuilder, PixelCanvas, RenderResult, TextureSpec, run_texture_cli

W = 40
STATE_H = 22
STATES_CONFIG = (
    {
        'name': 'normal',
        'body': (110, 70, 45, 255),
        'border': (85, 52, 32, 255),
        'highlight': (140, 95, 60, 255),
        'shadow': (75, 45, 28, 255),
    },
    {
        'name': 'hover',
        'body': (145, 100, 65, 255),
        'border': (115, 75, 45, 255),
        'highlight': (180, 135, 90, 255),
        'shadow': (105, 70, 40, 255),
    },
    {
        'name': 'selected',
        'body': (175, 130, 90, 255),
        'border': (140, 100, 65, 255),
        'highlight': (210, 170, 120, 255),
        'shadow': (130, 95, 60, 255),
    },
)

# 顶端/底端宽 32px，中部尖角最宽 38px（留 1px 给右侧落影）
RIGHT_X_PROFILE = (
    32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 38,
    38, 37, 36, 36, 35, 35, 34, 34, 33, 33, 32,
)


def _draw_bookmark_state(canvas: PixelCanvas, y_offset: int, cfg: dict) -> None:
    # 1. 1px 右下落影（允许延伸到下一态首行，由后续状态覆写）
    for local_y, rx in enumerate(RIGHT_X_PROFILE):
        y = y_offset + local_y + 1
        if y < canvas.height:
            canvas.hline(1, y, rx + 1, cfg['shadow'])

    # 2. 逐行填充主体、描边与斜面棱边
    selected = cfg['name'] == 'selected'
    for local_y, rx in enumerate(RIGHT_X_PROFILE):
        y = y_offset + local_y
        canvas.hline(0, y, rx + 1, cfg['body'])
        if not selected:
            canvas.pixel(0, y, cfg['border'])
        canvas.pixel(rx, y, cfg['border'])

        if local_y == 0:
            start_x = 0 if selected else 1
            canvas.hline(start_x, y, rx - start_x, cfg['highlight'])
        elif local_y == STATE_H - 1:
            start_x = 0 if selected else 1
            canvas.hline(start_x, y, rx - start_x, cfg['shadow'])

        if 0 < local_y <= 9 or local_y in (10, 11):
            canvas.pixel(rx - 1, y, cfg['highlight'])
        elif 12 <= local_y < STATE_H - 1:
            canvas.pixel(rx - 1, y, cfg['shadow'])

    # 3. 左侧 2px 竖向高光凹槽
    for local_y in range(2, STATE_H - 2):
        canvas.hline(4, y_offset + local_y, 2, cfg['highlight'])


def render() -> RenderResult:
    """绘制三态皮质书签标签条带。"""
    atlas = AtlasBuilder(W, STATE_H * len(STATES_CONFIG))
    for i, cfg in enumerate(STATES_CONFIG):
        y_offset = i * STATE_H
        atlas.reserve(cfg['name'], 0, y_offset, W, STATE_H)
        _draw_bookmark_state(atlas.canvas, y_offset, cfg)
    return atlas.build()


SPEC = TextureSpec(
    name='bookmark',
    description=__doc__ or '生成书签标签三态纹理',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/gui/bookmark_tab.png',
    render=render,
)

if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
