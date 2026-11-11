"""来源：书本外框导航、帮助侧签、欢迎页外链与全主题 9-slice 按钮皮肤 (ArchaeologyJournalScreen / JournalControls / UnsuspiciousUiTheme)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.palette import UI_PALETTE, run_source_preview
from drawlib import AtlasBuilder, Palette, PixelCanvas

ICONS: dict[tuple[int, int, str], tuple[str, ...]] = {
    # 浅羊皮纸底帮助问号 (Row 1, col 5) 与绿色侧签高亮反白问号 (Row 3, col 9)
    (5, 1, 'HELP'): (
        '..........',
        '..IIIII...',
        '.II...II..',
        '......II..',
        '.....II...',
        '....II....',
        '....II....',
        '..........',
        '....II....',
        '....II....',
    ),
    (9, 3, 'HELP_CREAM'): (
        '..........',
        '..KKKKK...',
        '.KK...KK..',
        '......KK..',
        '.....KK...',
        '....KK....',
        '....KK....',
        '..........',
        '....KK....',
        '....KK....',
    ),
}

EXTERNAL_LOGOS: tuple[tuple[str, int, tuple[str, ...]], ...] = (
    (
        'EXTERNAL_GITHUB',
        128,
        (
            '................',
            '.....111111.....',
            '...1111111111...',
            '..111K1111K111..',
            '..111KK11KK111..',
            '.111KKKKKKKK111.',
            '.11KKKKKKKKKK11.',
            '.11KKKKKKKKKK11.',
            '.11KKKKKKKKKK11.',
            '..11KKKKKKKK11..',
            '..1111KKKK1111..',
            '...11K1KK1111...',
            '....111KK111....',
            '.....11KK11.....',
            '.......11.......',
            '................',
        ),
    ),
    (
        'EXTERNAL_CURSEFORGE',
        144,
        (
            '................',
            '................',
            '..333333333333..',
            '.3554444444443..',
            '.34444444444443.',
            '..333444444443..',
            '.....34444433...',
            '......34443.....',
            '.....3444443....',
            '....354444443...',
            '...34444344443..',
            '..34443..344443.',
            '..3333....33333.',
            '................',
            '................',
            '................',
        ),
    ),
    (
        'EXTERNAL_MODRINTH',
        160,
        (
            '................',
            '.....677776.....',
            '...6788777776...',
            '..678766667776..',
            '.6776......6776.',
            '.6776..77..676..',
            '.676..7876......',
            '.676..77776.676.',
            '.6776..6777.676.',
            '..6776..6776776.',
            '..67776...6777..',
            '...6777766776...',
            '.....677776.....',
            '................',
            '................',
            '................',
        ),
    ),
)

PAGE_ARROW_SHAPE = (
    '.......................',
    '............DD.........',
    '............DHD........',
    '..DDDDDDDDDDDFHD.......',
    '.DFHHHHHHHHHHFFHD......',
    '.DFMMMMMMMMMMMMFHD.....',
    '.DFMMMMMMMMMMMMMFHD....',
    '.DFMMMMMMMMMMMMFHD.....',
    '.DFSSSSSSSSSSFFHD......',
    '..DDDDDDDDDDDFSD.......',
    '............DSD........',
    '............DD.........',
    '.......................',
)

PAGE_ARROW_STATES: tuple[tuple[str, int, Palette], ...] = (
    (
        'N',
        52,
        Palette({
            'D': (90, 61, 35, 255),
            'H': (250, 234, 192, 255),
            'F': (222, 194, 134, 255),
            'M': (212, 178, 112, 255),
            'S': (172, 136, 76, 255),
        }),
    ),
    (
        'H',
        65,
        Palette({
            'D': (74, 48, 22, 255),
            'H': (255, 244, 202, 255),
            'F': (244, 212, 128, 255),
            'M': (236, 198, 104, 255),
            'S': (194, 152, 68, 255),
        }),
    ),
    (
        'D',
        78,
        Palette({
            'D': (166, 146, 112, 190),
            'H': (238, 226, 198, 170),
            'F': (222, 206, 172, 170),
            'M': (216, 200, 166, 170),
            'S': (190, 172, 138, 170),
        }),
    ),
)

SIDE_TAB_OUTER = ((3, 0), (23, 0), (23, 19), (3, 19), (3, 18), (1, 18), (1, 16), (0, 16), (0, 3), (1, 3), (1, 1), (3, 1))
SIDE_TAB_INNER = ((4, 2), (22, 2), (22, 17), (4, 17), (4, 16), (2, 16), (2, 3), (4, 3))
SIDE_TABS: tuple[tuple[str, int, int, dict[str, str]], ...] = (
    ('TAB_MGMT_N', 50, 52, {'outline': '#563517', 'fill': '#C9923B', 'highlight': '#F2CF78', 'shadow': '#8A5724'}),
    ('TAB_MGMT_H', 74, 52, {'outline': '#462A10', 'fill': '#DEA64B', 'highlight': '#FFE398', 'shadow': '#A26A2C'}),
    ('TAB_HELP_N', 50, 72, {'outline': '#34473D', 'fill': '#7E977E', 'highlight': '#BDD0AC', 'shadow': '#526A57'}),
    ('TAB_HELP_H', 74, 72, {'outline': '#293A31', 'fill': '#93AD92', 'highlight': '#D4E6C4', 'shadow': '#64806A'}),
)

NINE_SLICE_BUTTONS = (
    ('BTN_PARCHMENT_NORMAL',   104, 52, (140, 111, 71, 255),  (243, 231, 200, 255), (255, 246, 223, 255), (222, 202, 161, 255), False),
    ('BTN_PARCHMENT_HOVER',    116, 52, (110, 75, 40, 255),   (250, 238, 206, 255), (255, 249, 230, 255), (226, 204, 152, 255), False),
    ('BTN_PARCHMENT_PRESSED',  128, 52, (94, 64, 34, 255),    (217, 192, 147, 255), (234, 215, 174, 255), (181, 151, 105, 255), True),
    ('BTN_PARCHMENT_SELECTED', 140, 52, (145, 100, 34, 255),  (234, 210, 159, 255), (252, 236, 188, 255), (206, 174, 114, 255), False),
    ('BTN_PARCHMENT_DISABLED', 152, 52, (191, 169, 134, 220), (236, 227, 204, 220), (244, 237, 218, 220), (220, 208, 180, 220), False),
    ('BTN_ACCENT_NORMAL',      104, 64, (109, 73, 34, 255),   (223, 196, 140, 255), (246, 226, 172, 255), (194, 164, 104, 255), False),
    ('BTN_ACCENT_HOVER',       116, 64, (86, 53, 23, 255),    (237, 210, 151, 255), (255, 240, 190, 255), (208, 176, 112, 255), False),
    ('BTN_ACCENT_PRESSED',     128, 64, (74, 46, 20, 255),    (201, 170, 111, 255), (226, 198, 138, 255), (166, 134, 78, 255),  True),
    ('BTN_ACCENT_DISABLED',    140, 64, (178, 159, 124, 220), (230, 218, 192, 220), (238, 229, 208, 220), (210, 196, 166, 220), False),
    ('BTN_QUIET_NORMAL',       104, 76, (170, 142, 100, 180), (238, 224, 190, 145), (252, 242, 216, 175), (216, 196, 156, 160), False),
)


def _paint_side_tab(c: PixelCanvas, pal: dict[str, str]) -> None:
    c.polygon(SIDE_TAB_OUTER, pal['outline'])
    c.polygon(SIDE_TAB_INNER, pal['fill'])
    c.hline(4, 2, 19, pal['highlight'])
    c.vline(2, 3, 13, pal['highlight'])
    c.hline(4, 17, 19, pal['shadow'])
    c.vline(22, 3, 15, pal['shadow'])


def apply(atlas: AtlasBuilder) -> None:
    """将书本导航、帮助问号、外链与按钮皮肤写入图集。"""
    for (col, row, name), rows in ICONS.items():
        atlas.place_grid_ascii(col, row, 10, 10, name, rows, UI_PALETTE)

    for name, u, grid in EXTERNAL_LOGOS:
        atlas.place_ascii(name, u, 18, grid, UI_PALETTE, expected_size=(16, 16))

    for tag, v, pal in PAGE_ARROW_STATES:
        atlas.place_ascii(f'PAGE_PREV_{tag}', 0, v, PAGE_ARROW_SHAPE, pal, expected_size=(23, 13), mirror_x=True)
        atlas.place_ascii(f'PAGE_NEXT_{tag}', 23, v, PAGE_ARROW_SHAPE, pal, expected_size=(23, 13))

    for name, u, v, pal in SIDE_TABS:
        atlas.paint(name, u, v, 24, 20, lambda c, p=pal: _paint_side_tab(c, p))

    for name, u, v, border, fill, top_b, bot_b, sunk in NINE_SLICE_BUTTONS:
        atlas.paint(
            name, u, v, 12, 12,
            lambda c, br=border, fl=fill, hi=bot_b if sunk else top_b, sh=top_b if sunk else bot_b: c.chamfer_panel(
                0, 0, 12, 12, border=br, fill=fl, highlight=hi, shade=sh, highlight_width=1, shade_width=1
            ),
        )


if __name__ == '__main__':
    raise SystemExit(run_source_preview('book_chrome', apply, bg='#7E977E'))
