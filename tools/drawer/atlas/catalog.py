"""来源：考古笔记目录工具栏与目录列表 (CatalogToolbar / CatalogPanel)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.palette import IconSpec, LayeredIcon, place_zone1_icons, run_source_preview
from drawlib import AsciiLayer, AtlasBuilder, flip_y_ascii

_ARROW_UP = (
    '..........',
    '....II....',
    '...IIII...',
    '..IIIIII..',
    '.IIIIIIII.',
    '....II....',
    '....II....',
    '....II....',
    '....II....',
    '..........',
)

_EYE_OPEN = (
    '..........',
    '..........',
    '..IIIIII..',
    '.ITTIITTI.',
    'ITTIIIITTI',
    '.ITTIITTI.',
    '..IIIIII..',
    '..........',
    '..........',
    '..........',
)

# 严格按 x + y = 9 绘制 1px 对角斜杠，并用 'x' (x + y = 8 及中央瞳孔右侧) 挖空隔离，消除断折与糊团
_EYE_HIDE_OVERLAY = AsciiLayer(
    rows=(
        '..........',
        '.......xI.',
        '......xIx.',
        '.....xIx..',
        '....xIx...',
        '...xIx....',
        '..xIx.....',
        '.xIx......',
        'xI........',
        '..........',
    ),
    cutout_token='x',
)

_STAR_TEMPLATE = (
    '..........',
    '....gg....',
    '...gYYg...',
    'gggGGGGggg',
    '.gGGYYGGg.',
    '..gGGGGg..',
    '..gGggGg..',
    '.gGg..gGg.',
    '.gg....gg.',
    '..........',
)

ICONS: dict[tuple[int, int, str], IconSpec] = {
    # —— 目录工具栏 (Row 0, cols 0..11) ——
    (0, 0, 'SEARCH'): (
        '..........',
        '..IIII....',
        '.IKAAAI...',
        '.IAaaaI...',
        '.IAaaaI...',
        '..IIII....',
        '......II..',
        '.......II.',
        '........I.',
        '..........',
    ),
    (1, 0, 'CLOSE'): (
        '..........',
        '.II....II.',
        '..II..II..',
        '...IIII...',
        '....II....',
        '....II....',
        '...IIII...',
        '..II..II..',
        '.II....II.',
        '..........',
    ),
    (2, 0, 'ARROW_UP'): _ARROW_UP,
    (3, 0, 'ARROW_DOWN'): flip_y_ascii(_ARROW_UP),
    (4, 0, 'SHOW_LOCKED'): _EYE_OPEN,
    (5, 0, 'HIDE_LOCKED'): LayeredIcon(layers=(_EYE_OPEN, _EYE_HIDE_OVERLAY)),
    (6, 0, 'SORT_DEFAULT'): (
        '..........',
        '.II.IIIII.',
        '..........',
        '..........',
        '.II.IIIII.',
        '..........',
        '..........',
        '.II.IIIII.',
        '..........',
        '..........',
    ),
    (7, 0, 'SORT_NAME'): (
        '..........',
        '...IIII...',
        '..II..II..',
        '..II..II..',
        '..IIIIII..',
        '..II..II..',
        '..II..II..',
        '..II..II..',
        '..II..II..',
        '..........',
    ),
    (8, 0, 'SORT_ITEM_COUNT'): (
        '..........',
        '...I..I...',
        '...I..I...',
        '.IIIIIIII.',
        '...I..I...',
        '...I..I...',
        '.IIIIIIII.',
        '...I..I...',
        '...I..I...',
        '..........',
    ),
    (9, 0, 'SORT_FAVORITE'): _STAR_TEMPLATE,
    (10, 0, 'SORT_UNLOCK'): (
        '..........',
        '...IIII...',
        '..II..II..',
        '..II......',
        '..IIIIII..',
        '.IIIIIIII.',
        '.IIggggII.',
        '.IIggggII.',
        '..IIIIII..',
        '..........',
    ),
    (11, 0, 'SORT_UPDATE_TIME'): (
        '..........',
        '.IIIIIIII.',
        '..I....I..',
        '...ISSII..',
        '....II....',
        '...ISSI...',
        '..ISSSSI..',
        '.IIIIIIII.',
        '..........',
        '..........',
    ),
    # —— 目录列表树节点与徽章 (Row 3, cols 0..3) ——
    (0, 3, 'TREE_COLLAPSED'): (
        '..........',
        '..I.......',
        '..II......',
        '..III.....',
        '..IIII....',
        '..IIII....',
        '..III.....',
        '..II......',
        '..I.......',
        '..........',
    ),
    (1, 3, 'TREE_EXPANDED'): (
        '..........',
        '..........',
        '..........',
        '.IIIIIIII.',
        '..IIIIII..',
        '...IIII...',
        '....II....',
        '..........',
        '..........',
        '..........',
    ),
    (2, 3, 'FAVORITE_BADGE'): _STAR_TEMPLATE,
    (3, 3, 'CHILD_BADGE'): (
        '..........',
        '..O.......',
        '..O.......',
        '..O..oo...',
        '..O.oOOo..',
        '..OOoOOo..',
        '.....oo...',
        '..........',
        '..........',
        '..........',
    ),
}


def apply(atlas: AtlasBuilder) -> None:
    """将目录来源图标写入图集 Zone 1。"""
    place_zone1_icons(atlas, ICONS)


if __name__ == '__main__':
    raise SystemExit(run_source_preview('catalog', apply, bg='#F3E7C8'))
