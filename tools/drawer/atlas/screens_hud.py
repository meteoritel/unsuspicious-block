"""来源：暗色独立页面与 HUD 专槽图标 (LootTableManagementScreen / PotteryWheelScreen / CatFavorHud)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.catalog import CLOSE_GLYPH
from atlas.palette import CAT_FAVOR_PALETTE, UI_PALETTE, IconSpec, LayeredIcon, place_zone1_icons, run_source_preview
from atlas.scenario import MINUS_GLYPH, PLUS_GLYPH, RESET_GLYPH
from drawlib import AtlasBuilder, Palette, PixelCanvas

ICONS: dict[tuple[int, int, str], IconSpec] = {
    # —— 管理界面暗底折叠箭头 (Row 3, cols 10..11) ——
    (10, 3, 'DARK_TREE_COLLAPSED'): (
        '..........',
        '..V.......',
        '..VV......',
        '..VVV.....',
        '..VVVs....',
        '..VVVs....',
        '..VVs.....',
        '..Vs......',
        '..s.......',
        '..........',
    ),
    (11, 3, 'DARK_TREE_EXPANDED'): (
        '..........',
        '..........',
        '.VVVVVVVV.',
        '..VVVVVs..',
        '...VVVs...',
        '....Vs....',
        '..........',
        '..........',
        '..........',
        '..........',
    ),
    # —— Row 4 (v=40): 管理屏追踪圆环 & 陶轮暗底高对比专槽 ——
    (0, 4, 'DARK_TRACKED'): (
        '..........',
        '...MMMM...',
        '..MMMMMM..',
        '.MMMMMMMM.',
        '.MMMMMMMM.',
        '.MMMMMMMM.',
        '.MMMMMMMM.',
        '..MMMMMM..',
        '...MMMM...',
        '..........',
    ),
    (1, 4, 'DARK_UNTRACKED'): (
        '..........',
        '...mmmm...',
        '..m....m..',
        '.m......m.',
        '.m......m.',
        '.m......m.',
        '.m......m.',
        '..m....m..',
        '...mmmm...',
        '..........',
    ),
    (2, 4, 'POTTERY_ENABLED'): (
        '..........',
        '........h.',
        '.......MM.',
        '......MM..',
        '.h...MM...',
        '.MM.MM....',
        '.MMMM.....',
        '..MM......',
        '..........',
        '..........',
    ),
    (3, 4, 'POTTERY_DISABLED'): (
        '..........',
        '...VVVV...',
        '..VV..VV..',
        '..VV..VV..',
        '.RRRRRRRR.',
        '.RRRddRRR.',
        '.RRRddRRR.',
        '.RRRRRRRR.',
        '.rrrrrrrr.',
        '..........',
    ),
    (4, 4, 'POTTERY_REDSTONE'): LayeredIcon(
        layers=((
            '..........',
            '....RR....',
            '...RyoR...',
            '...RcWR...',
            '....rr....',
            '....lw....',
            '....lm....',
            '....mw....',
            '....lw....',
            '..........',
        ),),
        palette=UI_PALETTE.extend({
            'r': '#B81814',
            'R': '#EC2018',
            'y': '#FFE000',
            'o': '#FF9400',
            'c': '#FFF894',
            'W': '#FFFFFF',
            'w': '#483218',
            'm': '#6C4C26',
            'l': '#946C38',
        }),
    ),
}

_POTTERY_LIGHTBOX_PALETTE = UI_PALETTE.extend({'I': '#101010'})
_POTTERY_LIGHTBOX_ICONS: dict[tuple[int, int, str], IconSpec] = {
    (8, 4, 'POTTERY_LIGHTBOX_CLOSE'): CLOSE_GLYPH,
    (9, 4, 'POTTERY_LIGHTBOX_RESET'): RESET_GLYPH,
    (10, 4, 'POTTERY_LIGHTBOX_PLUS'): PLUS_GLYPH,
    (11, 4, 'POTTERY_LIGHTBOX_MINUS'): MINUS_GLYPH,
}

CAT_FAVOR_GRID = (
    '................',
    '....rr....rr....',
    '...rWWr..rWWr...',
    '...rWWr..rWWr...',
    '.rr.rr....rr.rr.',
    'rWWr........rWWr',
    'rWWr..rrrr..rWWr',
    '.rr..rWWWWrr.rr.',
    '....rWWWWWWWr...',
    '...rWWppppWWWr..',
    '..rWWppppppWWWr.',
    '..rWWppppppWWWr.',
    '...rWWppppWWWr..',
    '...rWWWWWWWWWr..',
    '....rrrrrrrrr...',
    '................',
)

TOAST_FRAME_GRID = (
    '..OOOOOOOOOOOO..',
    '.ORHHHHHHHHHHRO.',
    'ORbttttttttttbRO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OHtFFFFFFFFFFsBO',
    'OBtFFFFFFFFFFsBO',
    'OBtFFFFFFFFFFsBO',
    'ORbssssssssssbRO',
    '.ORBBBBBBBBBBR0.'.replace('0', 'O'),
    '..OOOOOOOOOOOO..',
)

TOAST_SOCKET_GRID = (
    '.OOOOOOOOOOOOOO.',
    'OBssssssssssssBO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OsFFFFFFFFFFFFhO',
    'OBhhhhhhhhhhhhBO',
    '.OOOOOOOOOOOOOO.',
)

TOAST_FRAME_PALETTES: tuple[tuple[str, int, Palette], ...] = (
    (
        'TOAST_FRAME_BRONZE',
        64,
        Palette({
            'O': (20, 17, 23, 244),
            'H': (168, 126, 68, 255),
            'B': (118, 86, 46, 255),
            'b': (78, 59, 38, 245),
            'R': (234, 192, 102, 255),
            't': (74, 62, 68, 210),
            's': (18, 15, 21, 235),
            'F': (30, 28, 36, 228),
        }),
    ),
    (
        'TOAST_FRAME_GOLD',
        80,
        Palette({
            'O': (28, 19, 12, 248),
            'H': (236, 188, 76, 255),
            'B': (176, 124, 38, 255),
            'b': (118, 82, 28, 248),
            'R': (255, 236, 152, 255),
            't': (120, 92, 48, 215),
            's': (24, 17, 13, 238),
            'F': (38, 30, 24, 232),
        }),
    ),
    (
        'TOAST_FRAME_CRIMSON',
        96,
        Palette({
            'O': (24, 14, 18, 246),
            'H': (176, 86, 78, 255),
            'B': (124, 54, 48, 255),
            'b': (82, 38, 38, 245),
            'R': (216, 142, 114, 255),
            't': (88, 52, 56, 210),
            's': (20, 12, 16, 236),
            'F': (36, 24, 29, 228),
        }),
    ),
)

TOAST_SOCKET_PALETTES: tuple[tuple[str, int, Palette], ...] = (
    (
        'TOAST_ICON_SOCKET',
        112,
        Palette({
            'O': (96, 74, 46, 245),
            'B': (158, 124, 72, 255),
            's': (11, 10, 15, 242),
            'h': (66, 56, 62, 215),
            'F': (18, 17, 23, 236),
        }),
    ),
    (
        'TOAST_ICON_SOCKET_GOLD',
        128,
        Palette({
            'O': (146, 106, 36, 250),
            'B': (242, 202, 98, 255),
            's': (16, 12, 9, 244),
            'h': (126, 94, 46, 220),
            'F': (24, 19, 16, 238),
        }),
    ),
)


def _paint_toast_count_pill(c: PixelCanvas) -> None:
    c.rounded_rect(0, 0, 12, 12, radius=3, fill=(44, 36, 32, 226), border=(166, 126, 68, 242))
    c.hline(3, 1, 6, (226, 184, 104, 120))


def apply(atlas: AtlasBuilder) -> None:
    """将暗色界面、猫爪 HUD 与 Toast 9-slice 皮肤写入图集。"""
    place_zone1_icons(atlas, ICONS)
    place_zone1_icons(atlas, _POTTERY_LIGHTBOX_ICONS, palette=_POTTERY_LIGHTBOX_PALETTE)
    atlas.place_ascii('CAT_FAVOR_HUD', 196, 0, CAT_FAVOR_GRID, CAT_FAVOR_PALETTE, expected_size=(16, 16))
    for name, u, pal in TOAST_FRAME_PALETTES:
        atlas.place_ascii(name, u, 96, TOAST_FRAME_GRID, pal, expected_size=(16, 16))
    for name, u, pal in TOAST_SOCKET_PALETTES:
        atlas.place_ascii(name, u, 96, TOAST_SOCKET_GRID, pal, expected_size=(16, 16))
    atlas.paint('TOAST_COUNT_PILL', 144, 96, 12, 12, _paint_toast_count_pill)


if __name__ == '__main__':
    raise SystemExit(run_source_preview('screens_hud', apply, bg='#2E3036'))
