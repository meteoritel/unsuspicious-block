"""来源：场景条件页、状态角标、参数推荐与灯箱控件 (ScenarioPanel / ScenarioDetailPanel / ScenarioPageBuilder / ScenarioUi / ItemGridPanel)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.palette import UI_PALETTE, run_source_preview
from drawlib import AtlasBuilder, PixelCanvas

ICONS: dict[tuple[int, int, str], tuple[str, ...]] = {
    # —— 场景操作与灯箱控件 (Row 2, cols 3..11) ——
    (3, 2, 'EXPAND'): (
        '..........',
        '.III..III.',
        '.II....II.',
        '.I......I.',
        '..........',
        '..........',
        '.I......I.',
        '.II....II.',
        '.III..III.',
        '..........',
    ),
    (4, 2, 'FIT'): (
        '..........',
        '.I......I.',
        '.II....II.',
        '.III..III.',
        '..........',
        '..........',
        '.III..III.',
        '.II....II.',
        '.I......I.',
        '..........',
    ),
    (5, 2, 'PLUS'): (
        '..........',
        '....II....',
        '....II....',
        '....II....',
        '.IIIIIIII.',
        '.IIIIIIII.',
        '....II....',
        '....II....',
        '....II....',
        '..........',
    ),
    (6, 2, 'MINUS'): (
        '..........',
        '..........',
        '..........',
        '..........',
        '.IIIIIIII.',
        '.IIIIIIII.',
        '..........',
        '..........',
        '..........',
        '..........',
    ),
    (7, 2, 'PARAMS'): (
        '..........',
        '.....II...',
        '.IIIIggII.',
        '.....II...',
        '..........',
        '..II......',
        '.IggIIIII.',
        '..II......',
        '..........',
        '..........',
    ),
    (8, 2, 'CALCULATE'): (
        '..........',
        '..II......',
        '..III.....',
        '..IIII....',
        '..IIIII...',
        '..IIIII...',
        '..IIII....',
        '..III.....',
        '..II......',
        '..........',
    ),
    (9, 2, 'RECOMMEND'): (
        '....g.....',
        '....G..G..',
        '...gYg....',
        '..gGYGg...',
        'gGYYKYYGg.',
        '..gGYGg...',
        '...gYg....',
        '....G.....',
        '....g.....',
        '..........',
    ),
    (10, 2, 'CHEVRON_DOWN'): (
        '..........',
        '..........',
        '..........',
        '.II....II.',
        '..II..II..',
        '...IIII...',
        '....II....',
        '..........',
        '..........',
        '..........',
    ),
    (11, 2, 'CHEVRON_UP'): (
        '..........',
        '..........',
        '..........',
        '....II....',
        '...IIII...',
        '..II..II..',
        '.II....II.',
        '..........',
        '..........',
        '..........',
    ),
    # —— 条件分支引导 (Row 3, col 8) ——
    (8, 3, 'BRANCH_ARROW'): (
        '..........',
        '.I........',
        '.I....I...',
        '.I....II..',
        '.IIIIIII..',
        '......II..',
        '......I...',
        '..........',
        '..........',
        '..........',
    ),
}


def _paint_status_badge(c: PixelCanvas, bg: tuple, hi: tuple, kind: str) -> None:
    c.chamfer_panel(1, 1, 10, 10, border=(90, 62, 38, 255), fill=bg, highlight=hi, highlight_width=1, shade_width=0)
    if kind == 'uncomputed':
        c.pixels([(5, 4), (6, 4), (4, 5), (7, 5), (4, 6), (7, 6), (5, 7), (6, 7)], (122, 98, 71, 255))
    elif kind == 'pending':
        rim, sand = (158, 112, 22, 255), (224, 178, 42, 255)
        c.hline(4, 3, 4, rim).hline(4, 8, 4, rim)
        for px, py in ((4, 4), (7, 4), (5, 5), (6, 5), (5, 6), (6, 6), (4, 7), (7, 7)):
            c.pixel(px, py, sand if py in (5, 6, 7) else rim)
        c.pixels([(5, 7), (6, 7)], sand)
    elif kind == 'cached':
        chk_dark, chk = (44, 104, 44, 255), (72, 160, 68, 255)
        for px, py in ((3, 6), (4, 7), (5, 7), (6, 6), (7, 5), (8, 4)):
            c.pixel(px, py, chk).pixel(px, py + 1, chk_dark)
    else:
        x_dark, x_bright = (142, 46, 36, 255), (206, 78, 58, 255)
        for i in range(4):
            c.pixel(4 + i, 4 + i, x_bright).pixel(7 - i, 4 + i, x_bright)
            if i < 3:
                c.pixel(4 + i, 5 + i, x_dark)


def _paint_unknown_item(c: PixelCanvas) -> None:
    c.chamfer_panel(
        1, 1, 14, 14,
        border=(42, 34, 46, 255),
        fill=(68, 58, 72, 255),
        highlight=(100, 88, 98, 255),
        shade=(52, 44, 58, 255),
        highlight_width=2,
        shade_width=2,
    )
    c.pixels([(3, 4), (11, 3), (12, 9), (4, 12)], (116, 94, 74, 255))
    q_mask = (
        (6, 4), (7, 4), (8, 4), (9, 4), (5, 5), (10, 5), (9, 6), (10, 6),
        (8, 7), (9, 7), (7, 8), (8, 8), (7, 9), (7, 11), (8, 11),
    )
    c.pixels([(px + 1, py + 1) for px, py in q_mask], (164, 114, 38, 255))
    c.pixels(q_mask, (248, 218, 122, 255))


def apply(atlas: AtlasBuilder) -> None:
    """将场景与条件树来源图标、状态角标及 9-slice 面板写入图集。"""
    for (col, row, name), rows in ICONS.items():
        atlas.place_grid_ascii(col, row, 10, 10, name, rows, UI_PALETTE)

    badge_specs = (
        ('STATUS_UNCOMPUTED', (244, 232, 202, 255), (255, 246, 224, 255), 'uncomputed'),
        ('STATUS_PENDING',    (248, 236, 198, 255), (255, 248, 222, 255), 'pending'),
        ('STATUS_CACHED',     (232, 244, 222, 255), (246, 254, 238, 255), 'cached'),
        ('STATUS_FAILED',     (248, 228, 222, 255), (255, 242, 236, 255), 'failed'),
    )
    for idx, (label, bg, hi, kind) in enumerate(badge_specs):
        atlas.paint(label, 128 + idx * 12, 0, 12, 12, lambda c, b=bg, h=hi, k=kind: _paint_status_badge(c, b, h, k))

    atlas.paint('UNKNOWN_ITEM', 180, 0, 16, 16, _paint_unknown_item)
    atlas.paint(
        'SCENARIO_PANEL', 164, 52, 12, 12,
        lambda c: c.bevel_rect(0, 0, 12, 12, border='#b49b73', fill='#f2e5c6', top_left='#fff4d9', bottom='#dfcca4'),
    )


if __name__ == '__main__':
    raise SystemExit(run_source_preview('scenario', apply, bg='#F3E7C8'))
