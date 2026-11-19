"""来源：考古笔记目录工具栏与目录列表 (CatalogToolbar / CatalogPanel)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.palette import IconSpec, LayeredIcon, place_zone1_icons, run_source_preview
from drawlib import AsciiLayer, AtlasBuilder, PixelCanvas, flip_y_ascii

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

CLOSE_GLYPH = (
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
    (1, 0, 'CLOSE'): CLOSE_GLYPH,
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


def _paint_category_card(
    c: PixelCanvas,
    border: str,
    fill: str,
    highlight: str | None = None,
    shadow: str | None = None,
) -> None:
    c.rounded_rect(0, 0, 12, 12, radius=3, fill=fill, border=border)
    if highlight is not None:
        c.hline(3, 1, 6, highlight)
        c.vline(1, 3, 6, highlight)
    if shadow is not None:
        c.hline(3, 10, 6, shadow)
        c.vline(10, 3, 6, shadow)


def apply(atlas: AtlasBuilder) -> None:
    """将目录来源图标与九宫格圆角分类卡片 9-slice 写入图集。"""
    place_zone1_icons(atlas, ICONS)

    atlas.paint(
        'CARD_CATEGORY_NORMAL', 116, 76, 12, 12,
        lambda c: _paint_category_card(c, border='#C4A570', fill='#EFE2C2', highlight='#FFF7E6', shadow='#DECD9F'),
    )
    atlas.paint(
        'CARD_CATEGORY_HOVER', 128, 76, 12, 12,
        lambda c: _paint_category_card(c, border='#C89030', fill='#FBF2DA', highlight='#FFFFFF', shadow='#ECCF8E'),
    )
    atlas.paint(
        'CARD_CATEGORY_SELECTED', 140, 76, 12, 12,
        lambda c: _paint_category_card(c, border='#A47020', fill='#F7E0AF', highlight='#FFF3CE', shadow='#DEBC70'),
    )


if __name__ == '__main__':
    raise SystemExit(run_source_preview('catalog', apply, bg='#F3E7C8'))
