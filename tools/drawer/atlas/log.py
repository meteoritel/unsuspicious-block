"""来源：考古日志工具栏、条目徽章、详情按钮与通用状态 (LogToolbar / LogPanel / LogDetailPanel / CopyCoordinateButton)。"""
from __future__ import annotations

if __package__ in (None, ''):
    from pathlib import Path
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas.palette import IconSpec, LayeredIcon, UI_PALETTE, place_zone1_icons, run_source_preview
from drawlib import AtlasBuilder

_NOTE_BADGE_SHAPE = (
    '..........',
    '..BBBB....',
    '..BTTBB...',
    '..BTTTTB..',
    '..BTBBTB..',
    '..BTTTTB..',
    '..BTBBTB..',
    '..BTTTTB..',
    '..BBBBBB..',
    '..........',
)
_NOTE_BADGE_HOVER_PALETTE = UI_PALETTE.extend({'B': UI_PALETTE['b'], 'T': UI_PALETTE['K']})

_TRASH_BODY_BASE = (
    '..........',
    '..........',
    '..........',
    '..........',
    '..ILLLLI..',
    '..IWSSWI..',
    '..IWSSWI..',
    '..IWSSWI..',
    '...IIII...',
    '..........',
)

_TRASH_LID_CLOSED = (
    '..........',
    '....II....',
    '..ILLLLI..',
    '.IIIIIIII.',
    '..........',
    '..........',
    '..........',
    '..........',
    '..........',
    '..........',
)

_TRASH_LID_OPEN_FULL = (
    '.III......',
    'ILLI..II..',
    '..I..IKTI.',
    '.IIIIIIII.',
    '..........',
    '..........',
    '..........',
    '..........',
    '..........',
    '..........',
)

_COPY_BACK_SHEET = (
    '..........',
    '.LLLLLL...',
    '.LSSSSL...',
    '.LSSSSL...',
    '.LSSSSL...',
    '.LSSSSL...',
    '.LLLLLL...',
    '..........',
    '..........',
    '..........',
)

_COPY_FRONT_SHEET = (
    '..........',
    '..........',
    '..........',
    '...IIIII..',
    '...ITTTLI.',
    '...ITTTTI.',
    '...ITTTTI.',
    '...ITTTTI.',
    '...IIIIII.',
    '..........',
)

_CHECKBOX_FRAME = (
    '..........',
    '.IIIIIIII.',
    '.ITTTTTTI.',
    '.ITTTTTTI.',
    '.ITTTTTTI.',
    '.ITTTTTTI.',
    '.ITTTTTTI.',
    '.ITTTTTTI.',
    '.IIIIIIII.',
    '..........',
)

_CHECKBOX_TICK_OVERLAY = (
    '..........',
    '..........',
    '..........',
    '.......E..',
    '......Ee..',
    '..E..Ee...',
    '..eEEe....',
    '...ee.....',
    '..........',
    '..........',
)

ICONS: dict[tuple[int, int, str], IconSpec] = {
    # —— 日志工具栏、导航与通用状态 (Row 1, cols 0..4 & 6..11) ——
    (0, 1, 'BACK'): (
        '..........',
        '...I......',
        '..II......',
        '.IIIIIII..',
        'IIIIIIII..',
        '.II....II.',
        '..I....II.',
        '.......II.',
        '..IIIIII..',
        '..........',
    ),
    (1, 1, 'ENABLED'): (
        '..........',
        '........e.',
        '.......eE.',
        '......eEE.',
        '.e...eEE..',
        '.Ee.eEE...',
        '.EEeEE....',
        '..EEE.....',
        '...e......',
        '..........',
    ),
    (2, 1, 'DISABLED'): (
        '..........',
        '...IIII...',
        '..II..II..',
        '..II..II..',
        '.IIIIIIII.',
        '.IILLLLII.',
        '.IILTTLII.',
        '.IILLLLII.',
        '.IIIIIIII.',
        '..........',
    ),
    (3, 1, 'REDSTONE'): (
        '..........',
        '....rr....',
        '...rzzr...',
        '..rzRzzr..',
        '.rRRRRRRr.',
        '.rRRRRRRr.',
        '..rrrrrr..',
        '..........',
        '..........',
        '..........',
    ),
    (4, 1, 'GEAR'): (
        '....II....',
        '.I..II..I.',
        '..IIIIII..',
        '..II..II..',
        'III....III',
        'III....III',
        '..II..II..',
        '..IIIIII..',
        '.I..II..I.',
        '....II....',
    ),
    (6, 1, 'PENCIL'): (
        '..........',
        '......III.',
        '.....IRRI.',
        '....IGGI..',
        '...IGGI...',
        '..IGGI....',
        '..ITI.....',
        '.ITI......',
        '.II.......',
        '..........',
    ),
    (7, 1, 'CLOCK'): (
        '..........',
        '...IIII...',
        '..ITTTTI..',
        '.ITTITTTI.',
        '.ITTITTTI.',
        '.ITTIITTI.',
        '.ITTTTTTI.',
        '..ITTTTI..',
        '...IIII...',
        '..........',
    ),
    (8, 1, 'BIOME'): (
        '..........',
        '...eeee...',
        '..ehhEEe..',
        '.ehEEEEe..',
        '.eEEEEEEe.',
        '..eeeeee..',
        '....II....',
        '....II....',
        '..eeIIee..',
        '..........',
    ),
    (9, 1, 'DIMENSION'): (
        '..........',
        '..IIIIII..',
        '.IIppppII.',
        '.IIpPPpII.',
        '.IIPpPPII.',
        '.IIPPppII.',
        '.IIppppII.',
        '..IIIIII..',
        '..........',
        '..........',
    ),
    (10, 1, 'TRASH_EMPTY'): LayeredIcon(layers=(_TRASH_BODY_BASE, _TRASH_LID_CLOSED)),
    (11, 1, 'TRASH_FULL'): LayeredIcon(layers=(_TRASH_BODY_BASE, _TRASH_LID_OPEN_FULL)),
    # —— 日志批量操作与复制 (Row 2, cols 0..2) ——
    (0, 2, 'SQUARE'): (
        '..........',
        '.IIIIIIII.',
        '.ITTTTTTI.',
        '.IT....TI.',
        '.IT....TI.',
        '.IT....TI.',
        '.IT....TI.',
        '.ITTTTTTI.',
        '.IIIIIIII.',
        '..........',
    ),
    (1, 2, 'GROUP'): (
        '..........',
        '.III..III.',
        '.IGI..IGI.',
        '.III..III.',
        '..........',
        '.III..III.',
        '.IGI..IGI.',
        '.III..III.',
        '..........',
        '..........',
    ),
    (2, 2, 'COPY'): LayeredIcon(layers=(_COPY_BACK_SHEET, _COPY_FRONT_SHEET)),
    # —— 日志条目备注徽章与多选框 (Row 3, cols 4..7) ——
    (4, 3, 'NOTE_BADGE'): _NOTE_BADGE_SHAPE,
    (5, 3, 'NOTE_BADGE_HOVER'): LayeredIcon(layers=(_NOTE_BADGE_SHAPE,), palette=_NOTE_BADGE_HOVER_PALETTE),
    (6, 3, 'CHECKBOX_UNCHECKED'): _CHECKBOX_FRAME,
    (7, 3, 'CHECKBOX_CHECKED'): LayeredIcon(layers=(_CHECKBOX_FRAME, _CHECKBOX_TICK_OVERLAY)),
}


def apply(atlas: AtlasBuilder) -> None:
    """将日志与通用状态来源图标写入图集 Zone 1。"""
    place_zone1_icons(atlas, ICONS)


if __name__ == '__main__':
    raise SystemExit(run_source_preview('log', apply, bg='#F3E7C8'))
