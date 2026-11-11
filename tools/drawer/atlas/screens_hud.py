"""来源：暗色独立页面与 HUD 专槽图标 (LootTableManagementScreen / PotteryWheelScreen / CatFavorHud)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.palette import CAT_FAVOR_PALETTE, UI_PALETTE, run_source_preview
from drawlib import AtlasBuilder

ICONS: dict[tuple[int, int, str], tuple[str, ...]] = {
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
    (4, 4, 'POTTERY_REDSTONE'): (
        '..........',
        '....ZZ....',
        '...ZuuZ...',
        '..ZuuZZZ..',
        '.ZZZZZZZZ.',
        '.ZZZZZZZZ.',
        '..rrrrrr..',
        '..........',
        '..........',
        '..........',
    ),
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


def apply(atlas: AtlasBuilder) -> None:
    """将暗色界面与猫爪 HUD 图标写入图集。"""
    for (col, row, name), rows in ICONS.items():
        atlas.place_grid_ascii(col, row, 10, 10, name, rows, UI_PALETTE)
    atlas.place_ascii('CAT_FAVOR_HUD', 196, 0, CAT_FAVOR_GRID, CAT_FAVOR_PALETTE, expected_size=(16, 16))


if __name__ == '__main__':
    raise SystemExit(run_source_preview('screens_hud', apply, bg='#2E3036'))
