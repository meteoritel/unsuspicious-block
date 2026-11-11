#!/usr/bin/env python3
"""生成全模组统一 UI 图标与控件皮肤图集 (textures/gui/ui_atlas.png, 256x128 px)。"""

from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image, ImageDraw

ATLAS_W = 256
ATLAS_H = 128

# —— 调色板：羊皮纸暖棕墨水 + 铜金/自然语义点缀 + 暗底高对比专色 ——
C: dict[str, tuple[int, int, int, int]] = {
    ".": (0, 0, 0, 0),
    # 羊皮纸墨色系
    "D": (74, 50, 31, 255),      # 深橡木轮廓 (#4A321F)
    "I": (94, 64, 38, 255),      # 主墨线 (#5E4026)
    "L": (142, 106, 68, 255),    # 浅棕着色/副线 (#8E6A44)
    "W": (178, 132, 82, 255),    # 木纹/浅暖填充
    "S": (224, 198, 138, 255),   # 沙漏流沙/浅纸面
    "C": (245, 231, 190, 255),   # 浅米高光/助读反白 (#F5E7BE)
    "K": (255, 246, 223, 255),   # 极浅高光 (#FFF6DF)
    # 金属/铜金/收藏
    "g": (145, 100, 34, 255),    # 暗铜金
    "G": (206, 156, 54, 255),    # 琥珀金
    "Y": (244, 206, 102, 255),   # 亮金星芒
    # 镜片/群系/状态色
    "a": (150, 194, 208, 255),   # 放大镜玻璃柔青
    "A": (212, 236, 244, 255),   # 放大镜反光
    "e": (46, 106, 44, 255),     # 深森绿（描边）
    "E": (74, 154, 70, 255),     # 苍翠绿
    "h": (132, 212, 114, 255),   # 亮嫩绿
    "O": (79, 96, 51, 255),      # 橄榄子表绿 (与 CatalogPanel CHILD_COLOR 同色)
    "o": (118, 140, 78, 255),    # 浅橄榄绿
    "r": (134, 48, 36, 255),     # 暗朱红
    "R": (192, 76, 54, 255),     # 暖朱红
    "P": (138, 92, 188, 255),    # 维度幻紫
    "p": (176, 136, 222, 255),   # 维度浅紫
    "B": (123, 62, 24, 255),     # 笔记红棕 (#7B3E18)
    "b": (224, 144, 64, 255),    # 笔记悬停暖橙 (#E09040)
    # 暗底高对比（管理界面 & 陶轮台 & 帮扶反白）
    "d": (36, 38, 44, 255),      # 暗轮廓（陶轮暗石按钮护边）
    "s": (156, 162, 174, 255),   # 银灰副色
    "V": (232, 236, 242, 255),   # 暗底高对比冷白 (#E8ECF2)
    "m": (110, 114, 126, 255),   # 暗灰环
    "M": (120, 214, 106, 255),   # 管理屏跟踪绿 (#78D66A)
    "Z": (244, 76, 76, 255),     # 红石亮红
    "z": (146, 26, 26, 255),     # 红石暗红
    "T": (242, 226, 190, 255),   # 复选框纸底
    # 16x16 外部/特殊图标专用色
    "1": (44, 34, 30, 255),      # GitHub 暖炭黑
    "2": (82, 68, 62, 255),      # GitHub 高光
    "3": (152, 52, 22, 255),     # CurseForge 暗边
    "4": (234, 94, 44, 255),     # CurseForge 橙焰
    "5": (255, 166, 98, 255),    # CurseForge 高光
    "6": (18, 148, 72, 255),     # Modrinth 暗绿
    "7": (32, 214, 106, 255),    # Modrinth 翡翠绿
    "8": (128, 244, 170, 255),   # Modrinth 高光
}

# 10x10 图标模板字典：每张恰好 10 行 × 10 列
ICON_TEMPLATES: dict[tuple[int, int, str], tuple[str, ...]] = {
    # —— Row 0 (v=0): 目录 & 排序/显隐 ——
    (0, 0, "SEARCH"): (
        "..DDDD....",
        ".DAAAAI...",
        "DAAaaAID..",
        "DAaaLAID..",
        "DILLIIID..",
        ".DIIIID...",
        ".....DWD..",
        "......DWD.",
        ".......DWD",
        "........DD",
    ),
    (1, 0, "CLOSE"): (
        "..........",
        ".DL....LD.",
        ".DID..DID.",
        "..DIDDID..",
        "...DIID...",
        "...DIID...",
        "..DIDDID..",
        ".DID..DID.",
        ".DL....LD.",
        "..........",
    ),
    (2, 0, "ARROW_UP"): (
        "....DD....",
        "...DYYD...",
        "..DGIIGD..",
        ".DDILLIDD.",
        "...DIID...",
        "...DIID...",
        "...DIID...",
        "...DIID...",
        "...DIID...",
        "....DD....",
    ),
    (3, 0, "ARROW_DOWN"): (
        "....DD....",
        "...DIID...",
        "...DIID...",
        "...DIID...",
        "...DIID...",
        ".DDILLIDD.",
        "..DGIIGD..",
        "...DYYD...",
        "....DD....",
        "..........",
    ),
    (4, 0, "SHOW_LOCKED"): (
        "..........",
        "...DDDD...",
        ".DDKKKKDD.",
        "DKIDKIDKKD",
        "DKIDDDDKKD",
        ".DKIDDIKD.",
        "..DDDDDD..",
        "..........",
        "..........",
        "..........",
    ),
    (5, 0, "HIDE_LOCKED"): (
        "........D.",
        "...DDDDID.",
        ".DDKKKDKD.",
        "DKIDKIDD..",
        "DKIDDIKD..",
        ".DKIDDDKD.",
        ".DIDDDD...",
        "DID.......",
        "D.........",
        "..........",
    ),
    (6, 0, "SORT_DEFAULT"): (
        "..........",
        ".DD.DDDDD.",
        ".DI.ILLLL.",
        "..........",
        ".DD.DDDDD.",
        ".DI.ILLLL.",
        "..........",
        ".DD.DDDDD.",
        ".DI.ILLLL.",
        "..........",
    ),
    (7, 0, "SORT_NAME"): (
        "...DDDD...",
        "..DGGGGD..",
        ".DGI..IGD.",
        ".DI....ID.",
        ".DIIIIIIID",
        ".DIIIIIIID",
        ".DI....ID.",
        ".DI....ID.",
        ".DD....DD.",
        "..........",
    ),
    (8, 0, "SORT_ITEM_COUNT"): (
        "..........",
        "..DD..DD..",
        "..DI..DI..",
        ".DDDDDDDD.",
        "..DI..DI..",
        "..DI..DI..",
        ".DDDDDDDD.",
        "..DI..DI..",
        "..DD..DD..",
        "..........",
    ),
    (9, 0, "SORT_FAVORITE"): (
        "....DD....",
        "...DYYD...",
        "..DGGGGD..",
        "DDYYGGGGDD",
        "DYGGGGGGGD",
        ".DGGGGGGD.",
        "..DGGGGD..",
        ".DGGDDGGD.",
        ".DGD..DGD.",
        ".DD....DD.",
    ),
    (10, 0, "SORT_UNLOCK"): (
        "...DDDD...",
        "..DGGGGD..",
        "..DG..DD..",
        "..DG......",
        ".DDDDDDDD.",
        ".DGGGGGGD.",
        ".DGGDDGGD.",
        ".DGGDDGGD.",
        ".DDDDDDDD.",
        "..........",
    ),
    (11, 0, "SORT_UPDATE_TIME"): (
        ".DDDDDDDD.",
        ".DWWWWWWd.",
        "..DIYYID..",
        "...DIID...",
        "....DD....",
        "...DYYD...",
        "..DYYYYD..",
        ".DWWWWWWd.",
        ".DDDDDDDD.",
        "..........",
    ),
    # —— Row 1 (v=10): 导航、状态、工具与日志动作 ——
    (0, 1, "BACK"): (
        "..........",
        "...DD.....",
        "..DYYD....",
        ".DYGGDDDD.",
        "DYGIIIIIGD",
        ".DGGGGGGGD",
        "..DGGDDDD.",
        "...DD.....",
        "..........",
        "..........",
    ),
    (1, 1, "ENABLED"): (
        "..........",
        "........e.",
        ".......eEe",
        "......ehED",
        ".e...ehED.",
        "eEe.ehED..",
        ".ehEhED...",
        "..ehED....",
        "...eD.....",
        "..........",
    ),
    (2, 1, "DISABLED"): (
        "...DDDD...",
        "..DLLLLD..",
        "..DL..LD..",
        "..DL..LD..",
        ".DDDDDDDD.",
        ".DWWDDWWD.",
        ".DWWD.WWD.",
        ".DWWDDWWD.",
        ".DDDDDDDD.",
        "..........",
    ),
    (3, 1, "REDSTONE"): (
        "....rr....",
        "...rZZr...",
        "..rZCKZr..",
        ".rZZZZZZr.",
        ".rZZzzZZr.",
        "rZZzZZzZZr",
        ".rZZZZZZr.",
        "..rrrrrr..",
        "..........",
        "..........",
    ),
    (4, 1, "GEAR"): (
        "...D..D...",
        ".D.DIID.D.",
        "DDGIYYIGDD",
        ".DGIDDIGD.",
        "DGI....IGD",
        "DGI....IGD",
        ".DGIDDIGD.",
        "DDGIIIIGDD",
        ".D.DIID.D.",
        "...D..D...",
    ),
    (5, 1, "HELP"): (
        "..DDDDDD..",
        ".DGGGGGGD.",
        ".DG....GD.",
        ".....DGGD.",
        "....DGGD..",
        "....DGD...",
        "....DD....",
        "..........",
        "....DD....",
        "....DD....",
    ),
    (6, 1, "PENCIL"): (
        "......DD..",
        ".....DRRD.",
        "....DWWWRD",
        "...DGGGGD.",
        "..DGGGGD..",
        ".DGGGGD...",
        ".DCCCID...",
        ".DCCID....",
        ".DDID.....",
        ".DDD......",
    ),
    (7, 1, "CLOCK"): (
        "....DD....",
        "..DDDDDD..",
        ".DCCCCCCd.",
        "DCCCDCCCCD",
        "DCCCDCCCCD",
        "DCCCDDDCCD",
        "DCCCCCCCCD",
        ".DCCCCCCd.",
        "..DDDDDD..",
        "..........",
    ),
    (8, 1, "BIOME"): (
        "....ee....",
        "..eehhee..",
        ".ehEEEEhe.",
        ".eEEEEEEe.",
        "..eEEEEe..",
        "...DDDD...",
        "....DI....",
        "....DI....",
        "..eeDDee..",
        "..........",
    ),
    (9, 1, "DIMENSION"): (
        "....DD....",
        "..DDPPDD..",
        ".DPpPPPpD.",
        ".DPPPPPPD.",
        ".DPPpPpPD.",
        ".DPPPPPPD.",
        ".DPpPPPpD.",
        "..DDPPDD..",
        "....DD....",
        "..........",
    ),
    (10, 1, "TRASH_EMPTY"): (
        "...DDDD...",
        ".DDDDDDDD.",
        ".DKGGGGKD.",
        "..DWWWWd..",
        "..DWDDWd..",
        "..DWDDWd..",
        "..DWDDWd..",
        "...DWWD...",
        "...DDDD...",
        "..........",
    ),
    (11, 1, "TRASH_FULL"): (
        ".DDKKKKD..",
        ".DYKCCRRD.",
        "..DDDDDDD.",
        "..DWWWWd..",
        "..DWDDWd..",
        "..DWDDWd..",
        "..DWDDWd..",
        "...DWWD...",
        "...DDDD...",
        "..........",
    ),
    # —— Row 2 (v=20): 批量操作、复制与场景按钮 ——
    (0, 2, "SQUARE"): (
        "..........",
        ".DDDDDDDD.",
        ".DCCCCCCd.",
        ".DC....CD.",
        ".DC....CD.",
        ".DC....CD.",
        ".DC....CD.",
        ".DCCCCCCd.",
        ".DDDDDDDD.",
        "..........",
    ),
    (1, 2, "GROUP"): (
        "..........",
        ".DDD..DDD.",
        ".DYD..DCD.",
        ".DDD..DDD.",
        "..........",
        ".DDD..DDD.",
        ".DCD..DYD.",
        ".DDD..DDD.",
        "..........",
        "..........",
    ),
    # COPY 故意留出第 0 行与第 9 行透明，保证 14x10 按钮内无任何边缘碰撞
    (2, 2, "COPY"): (
        "..........",
        ".DDDDD....",
        ".DKKKDDDD.",
        ".DK.DKLLD.",
        ".DK.DKKKD.",
        ".DD.DKLLD.",
        "....DKKKD.",
        "....DCCCD.",
        "....DDDDD.",
        "..........",
    ),
    (3, 2, "EXPAND"): (
        ".DDDD.DDDD",
        ".DYYD.DYYD",
        ".DYID.DIYD",
        ".DD.D.D.DD",
        "..........",
        "..........",
        ".DD.D.D.DD",
        ".DYID.DIYD",
        ".DYYD.DYYD",
        ".DDDD.DDDD",
    ),
    (4, 2, "FIT"): (
        ".DD....DD.",
        ".DID..DID.",
        "DDDD..DDDD",
        "..........",
        "....DD....",
        "....DD....",
        "..........",
        "DDDD..DDDD",
        ".DID..DID.",
        ".DD....DD.",
    ),
    (5, 2, "PLUS"): (
        "..........",
        "....DD....",
        "....DI....",
        "....DI....",
        ".DDDIIDDD.",
        ".DIIIIILD.",
        "....DI....",
        "....DI....",
        "....DD....",
        "..........",
    ),
    (6, 2, "MINUS"): (
        "..........",
        "..........",
        "..........",
        "..........",
        ".DDDDDDDD.",
        ".DIIIIILD.",
        "..........",
        "..........",
        "..........",
        "..........",
    ),
    (7, 2, "PARAMS"): (
        "..........",
        "..DDD.....",
        ".DYGYLLLL.",
        "..DDD.....",
        "..........",
        ".....DDD..",
        ".LLLLGYGD.",
        ".....DDD..",
        "..........",
        "..........",
    ),
    (8, 2, "CALCULATE"): (
        "..........",
        "..DD......",
        "..DYD.....",
        "..DYGD....",
        "..DYGGD...",
        "..DYGGD...",
        "..DYGD....",
        "..DYD.....",
        "..DD......",
        "..........",
    ),
    (9, 2, "RECOMMEND"): (
        "....DD....",
        "...DYYD.Y.",
        "...DYGDYKY",
        ".DDYKKYDY.",
        "DYYKGGKYD.",
        ".DDYGGYDD.",
        "...DGGD...",
        "...DYYD...",
        "....DD....",
        "..........",
    ),
    (10, 2, "CHEVRON_DOWN"): (
        "..........",
        "..........",
        ".DD....DD.",
        ".DYD..DYD.",
        "..DYDDYD..",
        "...DGGD...",
        "....DD....",
        "..........",
        "..........",
        "..........",
    ),
    (11, 2, "CHEVRON_UP"): (
        "..........",
        "....DD....",
        "...DGGD...",
        "..DYDDYD..",
        ".DYD..DYD.",
        ".DD....DD.",
        "..........",
        "..........",
        "..........",
        "..........",
    ),
    # —— Row 3 (v=30): 目录树/列表行内标识、复选框、侧签与暗底树 ——
    (0, 3, "TREE_COLLAPSED"): (
        "..........",
        "..DD......",
        "..DYD.....",
        "..DIGD....",
        "..DIGGD...",
        "..DIGD....",
        "..DYD.....",
        "..DD......",
        "..........",
        "..........",
    ),
    (1, 3, "TREE_EXPANDED"): (
        "..........",
        "..........",
        ".DDDDDDDD.",
        ".DYYYYYYD.",
        "..DGGGGD..",
        "...DIID...",
        "....DD....",
        "..........",
        "..........",
        "..........",
    ),
    (2, 3, "FAVORITE_BADGE"): (
        "....gg....",
        "...gYYg...",
        ".ggYGGYgg.",
        "gYYGGGGYYg",
        ".gGGGGGGg.",
        "..gGGGGg..",
        ".gGGggGGg.",
        ".gGg..gGg.",
        "..........",
        "..........",
    ),
    (3, 3, "CHILD_BADGE"): (
        "..........",
        ".OO.......",
        ".Oo.......",
        ".Oo.OOOOO.",
        ".Oo.OoooO.",
        ".OoooOoOO.",
        "..OO.OOOO.",
        "..........",
        "..........",
        "..........",
    ),
    (4, 3, "NOTE_BADGE"): (
        "..........",
        ".......BB.",
        "......BRB.",
        ".....BWWD.",
        "....BWWB..",
        "...BWWB...",
        "..BCCB....",
        "..BBB.....",
        "..........",
        "..........",
    ),
    (5, 3, "NOTE_BADGE_HOVER"): (
        "..........",
        ".......bb.",
        "......bYb.",
        ".....bYYb.",
        "....bYYb..",
        "...bYYb...",
        "..bCCb....",
        "..bbb.....",
        "..........",
        "..........",
    ),
    (6, 3, "CHECKBOX_UNCHECKED"): (
        "..........",
        ".DDDDDDDD.",
        ".DLLLLLLC.",
        ".DLTTTTTC.",
        ".DLTTTTTC.",
        ".DLTTTTTC.",
        ".DLTTTTTC.",
        ".DCCCCCCC.",
        ".DDDDDDDD.",
        "..........",
    ),
    (7, 3, "CHECKBOX_CHECKED"): (
        ".........e",
        ".DDDDDDDeh",
        ".DLLLLLeEe",
        ".DLTTTeED.",
        ".DeTTeED..",
        ".DeEeED...",
        ".DLeeTD...",
        ".DCCCCCCC.",
        ".DDDDDDDD.",
        "..........",
    ),
    (8, 3, "BRANCH_ARROW"): (
        "..........",
        ".DD.......",
        ".DL...DD..",
        ".DL...DGD.",
        ".DLLLLDGGD",
        "..DDDDDGD.",
        "......DD..",
        "..........",
        "..........",
        "..........",
    ),
    (9, 3, "HELP_CREAM"): (
        "..KKKKKK..",
        ".KCCCCCCK.",
        ".KC....CK.",
        ".....KCCK.",
        "....KCCK..",
        "....KCK...",
        "....KK....",
        "..........",
        "....KK....",
        "....KK....",
    ),
    (10, 3, "DARK_TREE_COLLAPSED"): (
        "..........",
        "..dd......",
        "..dVd.....",
        "..dVsVd...",
        "..dVssVd..",
        "..dVsVd...",
        "..dVd.....",
        "..dd......",
        "..........",
        "..........",
    ),
    (11, 3, "DARK_TREE_EXPANDED"): (
        "..........",
        "..........",
        ".dddddddd.",
        ".dVVVVVVd.",
        "..dssssd..",
        "...dssdd..",
        "....dd....",
        "..........",
        "..........",
        "..........",
    ),
    # —— Row 4 (v=40): 管理屏追踪状态 & 陶轮暗底高对比专槽 ——
    (0, 4, "DARK_TRACKED"): (
        "..........",
        "...eeee...",
        "..ehhhhe..",
        ".ehMMMMhe.",
        ".eMMMMMMe.",
        ".eMMMMMMe.",
        "..eMMMMe..",
        "...eeee...",
        "..........",
        "..........",
    ),
    (1, 4, "DARK_UNTRACKED"): (
        "..........",
        "...dddd...",
        "..dmmmm...",
        ".dm....md.",
        ".dm....md.",
        ".dm....md.",
        "..dmmmmd..",
        "...dddd...",
        "..........",
        "..........",
    ),
    (2, 4, "POTTERY_ENABLED"): (
        "..........",
        "........d.",
        ".......dhd",
        "......dMhd",
        ".d...dMEd.",
        "dhd.dMEd..",
        ".dMhMEd...",
        "..dMEd....",
        "...dd.....",
        "..........",
    ),
    (3, 4, "POTTERY_DISABLED"): (
        "...dddd...",
        "..dVVVVd..",
        "..dV..Vd..",
        "..dV..Vd..",
        ".dddddddd.",
        ".dRsddRsd.",
        ".dRsd.Rsd.",
        ".dRsddRsd.",
        ".dddddddd.",
        "..........",
    ),
    (4, 4, "POTTERY_REDSTONE"): (
        "....dd....",
        "...dZZd...",
        "..dZKKZd..",
        ".dZZZZZZd.",
        ".dZZzzZZd.",
        "dZZzZZzZZd",
        ".dZZzzZZd.",
        "..dzzzzd..",
        "...dddd...",
        "..........",
    ),
}


def put_template(atlas: Image.Image, u: int, v: int, rows: tuple[str, ...]) -> None:
    expected_h = len(rows)
    expected_w = len(rows[0])
    for y, row in enumerate(rows):
        if len(row) != expected_w:
            raise ValueError(f"Row width mismatch at ({u},{v}) line {y}: {len(row)} != {expected_w}")
        for x, ch in enumerate(row):
            if ch not in C:
                raise KeyError(f"Unknown palette token {ch!r} at ({u},{v}) ({x},{y})")
            atlas.putpixel((u + x, v + y), C[ch])


def draw_status_badges(atlas: Image.Image) -> None:
    """绘制 Zone 2 的 12x12 四态场景计算角标。"""
    configs = [
        # (u, bg, highlight, glyph_drawer)
        (128, (244, 232, 202, 255), (255, 246, 224, 255), "uncomputed"),
        (140, (248, 236, 198, 255), (255, 248, 222, 255), "pending"),
        (152, (232, 244, 222, 255), (246, 254, 238, 255), "cached"),
        (164, (248, 228, 222, 255), (255, 242, 236, 255), "failed"),
    ]
    border = (90, 62, 38, 255)
    for u, bg, hi, kind in configs:
        # 切角 12x12 纸面底座
        for y in range(1, 11):
            for x in range(1, 11):
                if (x in (1, 10)) and (y in (1, 10)):
                    continue
                is_edge = (
                    x in (1, 10)
                    or y in (1, 10)
                    or (x in (2, 9) and y in (2, 9))
                )
                color = border if is_edge else (hi if y == 2 or x == 2 else bg)
                atlas.putpixel((u + x, y), color)

        if kind == "uncomputed":
            dot = (122, 98, 71, 255)
            for px_x, px_y in [(5, 4), (6, 4), (4, 5), (7, 5), (4, 6), (7, 6), (5, 7), (6, 7)]:
                atlas.putpixel((u + px_x, px_y), dot)
        elif kind == "pending":
            rim = (158, 112, 22, 255)
            sand = (224, 178, 42, 255)
            for px_x in range(4, 8):
                atlas.putpixel((u + px_x, 3), rim)
                atlas.putpixel((u + px_x, 8), rim)
            for px_x, px_y in [(4, 4), (7, 4), (5, 5), (6, 5), (5, 6), (6, 6), (4, 7), (7, 7)]:
                atlas.putpixel((u + px_x, px_y), sand if px_y in (5, 6, 7) else rim)
            atlas.putpixel((u + 5, 7), sand)
            atlas.putpixel((u + 6, 7), sand)
        elif kind == "cached":
            chk_dark = (44, 104, 44, 255)
            chk = (72, 160, 68, 255)
            for px_x, px_y in [(3, 6), (4, 7), (5, 7), (6, 6), (7, 5), (8, 4)]:
                atlas.putpixel((u + px_x, px_y), chk)
                atlas.putpixel((u + px_x, px_y + 1), chk_dark)
        else:
            x_dark = (142, 46, 36, 255)
            x_bright = (206, 78, 58, 255)
            for i in range(4):
                atlas.putpixel((u + 4 + i, 4 + i), x_bright)
                atlas.putpixel((u + 7 - i, 4 + i), x_bright)
                if i < 3:
                    atlas.putpixel((u + 4 + i, 5 + i), x_dark)


def draw_unknown_item(atlas: Image.Image, u: int, v: int) -> None:
    """绘制 16x16 考古神秘方块 + 金棕问号，风格融入羊皮纸与遗迹氛围。"""
    # 16x16 深石遗迹底块（斜角切边）
    outline = (42, 34, 46, 255)
    body = (68, 58, 72, 255)
    shade = (52, 44, 58, 255)
    dust = (116, 94, 74, 255)
    hi = (100, 88, 98, 255)
    for y in range(1, 15):
        for x in range(1, 15):
            if (x in (1, 14) and y in (1, 14)):
                continue
            edge = x in (1, 14) or y in (1, 14) or (x in (2, 13) and y in (2, 13))
            if edge:
                col = outline
            elif y <= 3 or x <= 3:
                col = hi
            elif y >= 12 or x >= 12:
                col = shade
            else:
                col = body
            atlas.putpixel((u + x, v + y), col)
    # 边角风沙蚀痕
    for px_x, px_y in [(3, 4), (11, 3), (12, 9), (4, 12)]:
        atlas.putpixel((u + px_x, v + px_y), dust)
    # 中心亮金问号 + 暖铜描边
    q_hi = (248, 218, 122, 255)
    q_sh = (164, 114, 38, 255)
    q_mask = [
        (6, 4), (7, 4), (8, 4), (9, 4),
        (5, 5), (10, 5),
        (9, 6), (10, 6),
        (8, 7), (9, 7),
        (7, 8), (8, 8),
        (7, 9),
        (7, 11), (8, 11),
    ]
    for px_x, px_y in q_mask:
        atlas.putpixel((u + px_x + 1, v + px_y + 1), q_sh)
    for px_x, px_y in q_mask:
        atlas.putpixel((u + px_x, v + px_y), q_hi)


def draw_cat_favor_hud(atlas: Image.Image, u: int, v: int) -> None:
    """绘制 16x16 中性白色/灰阶可染色猫爪图标（供 CatFavorHud 着色渲染）。"""
    rim = (128, 128, 128, 255)
    pad = (206, 206, 206, 255)
    hi = (255, 255, 255, 255)
    grid = (
        "................",
        "....rr....rr....",
        "...rWWr..rWWr...",
        "...rWWr..rWWr...",
        ".rr.rr....rr.rr.",
        "rWWr........rWWr",
        "rWWr..rrrr..rWWr",
        ".rr..rWWWWrr.rr.",
        "....rWWWWWWWr...",
        "...rWWppppWWWr..",
        "..rWWppppppWWWr.",
        "..rWWppppppWWWr.",
        "...rWWppppWWWr..",
        "...rWWWWWWWWWr..",
        "....rrrrrrrrr...",
        "................",
    )
    tok = {".": (0, 0, 0, 0), "r": rim, "p": pad, "W": hi}
    for y, row in enumerate(grid):
        for x, ch in enumerate(row):
            atlas.putpixel((u + x, v + y), tok[ch])


def draw_external_logos(atlas: Image.Image) -> None:
    """绘制 16x16 锐利像素风外链图标：GitHub / CurseForge / Modrinth。"""
    tok = {
        ".": (0, 0, 0, 0),
        "1": C["1"], "2": C["2"],
        "3": C["3"], "4": C["4"], "5": C["5"],
        "6": C["6"], "7": C["7"], "8": C["8"],
        "C": C["C"], "K": C["K"],
    }
    github = (
        "................",
        ".....111111.....",
        "...1111111111...",
        "..111K1111K111..",
        "..111KK11KK111..",
        ".111KKKKKKKK111.",
        ".11KKKKKKKKKK11.",
        ".11KKKKKKKKKK11.",
        ".11KKKKKKKKKK11.",
        "..11KKKKKKKK11..",
        "..1111KKKK1111..",
        "...11K1KK1111...",
        "....111KK111....",
        ".....11KK11.....",
        ".......11.......",
        "................",
    )
    curseforge = (
        "................",
        "................",
        "..333333333333..",
        ".3554444444443..",
        ".34444444444443.",
        "..333444444443..",
        ".....34444433...",
        "......34443.....",
        ".....3444443....",
        "....354444443...",
        "...34444344443..",
        "..34443..344443.",
        "..3333....33333.",
        "................",
        "................",
        "................",
    )
    modrinth = (
        "................",
        ".....677776.....",
        "...6788777776...",
        "..678766667776..",
        ".6776......6776.",
        ".6776..77..676..",
        ".676..7876......",
        ".676..77776.676.",
        ".6776..6777.676.",
        "..6776..6776776.",
        "..67776...6777..",
        "...6777766776...",
        ".....677776.....",
        "................",
        "................",
        "................",
    )
    for u, rows in [(128, github), (144, curseforge), (160, modrinth)]:
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                atlas.putpixel((u + x, 18 + y), tok[ch])


def draw_page_arrows(atlas: Image.Image) -> None:
    """绘制 23x13 上/下一页羊皮纸质感翻页箭头（常态 / 悬停 / 禁用）。"""
    # 以右指向为准（23x13），左指向做水平镜像
    base_shape = [
        ".......................",
        "............DD.........",
        "............DHD........",
        "..DDDDDDDDDDDFHD.......",
        ".DFHHHHHHHHHHFFHD......",
        ".DFMMMMMMMMMMMMFHD.....",
        ".DFMMMMMMMMMMMMMFHD....",
        ".DFMMMMMMMMMMMMFHD.....",
        ".DFSSSSSSSSSSFFHD......",
        "..DDDDDDDDDDDFSD.......",
        "............DSD........",
        "............DD.........",
        ".......................",
    ]
    palettes = {
        "normal": {
            "D": (90, 61, 35, 255),
            "H": (250, 234, 192, 255),
            "F": (222, 194, 134, 255),
            "M": (212, 178, 112, 255),
            "S": (172, 136, 76, 255),
        },
        "hover": {
            "D": (74, 48, 22, 255),
            "H": (255, 244, 202, 255),
            "F": (244, 212, 128, 255),
            "M": (236, 198, 104, 255),
            "S": (194, 152, 68, 255),
        },
        "disabled": {
            "D": (166, 146, 112, 190),
            "H": (238, 226, 198, 170),
            "F": (222, 206, 172, 170),
            "M": (216, 200, 166, 170),
            "S": (190, 172, 138, 170),
        },
    }
    state_v = {"normal": 52, "hover": 65, "disabled": 78}
    for state, v in state_v.items():
        pal = palettes[state]
        for y, row in enumerate(base_shape):
            for x, ch in enumerate(row):
                col = (0, 0, 0, 0) if ch == "." else pal[ch]
                # 右箭头 at u=23，左箭头（镜像）at u=0
                atlas.putpixel((23 + x, v + y), col)
                atlas.putpixel((0 + (22 - x), v + y), col)


def draw_side_tabs(atlas: Image.Image) -> None:
    """绘制 24x20 书本左侧梯级切角标签底板（严格贴合剪影的常态与悬停态）。"""
    tabs = [
        (
            50, 52,
            {
                "outline": "#563517",
                "fill": "#C9923B",
                "highlight": "#F2CF78",
                "shadow": "#8A5724",
            },
        ),
        (
            74, 52,
            {
                "outline": "#462A10",
                "fill": "#DEA64B",
                "highlight": "#FFE398",
                "shadow": "#A26A2C",
            },
        ),
        (
            50, 72,
            {
                "outline": "#34473D",
                "fill": "#7E977E",
                "highlight": "#BDD0AC",
                "shadow": "#526A57",
            },
        ),
        (
            74, 72,
            {
                "outline": "#293A31",
                "fill": "#93AD92",
                "highlight": "#D4E6C4",
                "shadow": "#64806A",
            },
        ),
    ]
    draw = ImageDraw.Draw(atlas)
    outer = [
        (3, 0), (23, 0), (23, 19), (3, 19),
        (3, 18), (1, 18), (1, 16), (0, 16),
        (0, 3), (1, 3), (1, 1), (3, 1),
    ]
    inner = [
        (4, 2), (22, 2), (22, 17), (4, 17),
        (4, 16), (2, 16), (2, 3), (4, 3),
    ]
    for u, v, pal in tabs:
        draw.polygon([(u + x, v + y) for x, y in outer], fill=pal["outline"])
        draw.polygon([(u + x, v + y) for x, y in inner], fill=pal["fill"])
        draw.line([(u + 4, v + 2), (u + 22, v + 2)], fill=pal["highlight"])
        draw.line([(u + 2, v + 3), (u + 2, v + 15)], fill=pal["highlight"])
        draw.line([(u + 4, v + 17), (u + 22, v + 17)], fill=pal["shadow"])
        draw.line([(u + 22, v + 3), (u + 22, v + 17)], fill=pal["shadow"])


def draw_nine_slice_button(
    atlas: Image.Image,
    u: int,
    v: int,
    border: tuple[int, int, int, int],
    fill: tuple[int, int, int, int],
    top_bevel: tuple[int, int, int, int],
    bottom_bevel: tuple[int, int, int, int],
    sunken: bool = False,
) -> None:
    """绘制 12x12 带 1px 圆角切边与内斜面的九宫格按钮纹理（corner=2）。"""
    for y in range(12):
        for x in range(12):
            # 四个最外顶点留透明，形成柔和像素圆角
            if (x in (0, 11)) and (y in (0, 11)):
                continue
            is_border = (
                x in (0, 11)
                or y in (0, 11)
                or (x in (1, 10) and y in (1, 10))
            )
            if is_border:
                col = border
            elif (not sunken and (y == 1 or x == 1)) or (sunken and (y == 1 or x == 1)):
                col = top_bevel if not sunken else bottom_bevel
            elif y == 10 or x == 10:
                col = bottom_bevel if not sunken else top_bevel
            else:
                col = fill
            atlas.putpixel((u + x, v + y), col)


def draw_nine_slice_panel(atlas: Image.Image, u: int, v: int) -> None:
    """绘制 12x12 场景底纹九宫格（corner=3，替换原独立 scenario_panel.png）。"""
    draw = ImageDraw.Draw(atlas)
    draw.rectangle((u, v, u + 11, v + 11), fill="#f2e5c6", outline="#b49b73")
    draw.line((u + 1, v + 1, u + 10, v + 1), fill="#fff4d9")
    draw.line((u + 1, v + 1, u + 1, v + 10), fill="#fff4d9")
    draw.line((u + 1, v + 10, u + 10, v + 10), fill="#dfcca4")


def assert_non_overlapping(boxes: list[tuple[str, int, int, int, int]]) -> None:
    for i, (name_a, x0, y0, w0, h0) in enumerate(boxes):
        if x0 < 0 or y0 < 0 or x0 + w0 > ATLAS_W or y0 + h0 > ATLAS_H:
            raise AssertionError(f"{name_a} out of atlas bounds: {(x0, y0, w0, h0)}")
        for name_b, x1, y1, w1, h1 in boxes[i + 1 :]:
            if x0 < x1 + w1 and x0 + w0 > x1 and y0 < y1 + h1 and y0 + h0 > y1:
                raise AssertionError(f"Atlas region overlap: {name_a} and {name_b}")


def generate_atlas() -> tuple[Image.Image, list[tuple[str, int, int, int, int]]]:
    atlas = Image.new("RGBA", (ATLAS_W, ATLAS_H), (0, 0, 0, 0))
    boxes: list[tuple[str, int, int, int, int]] = []

    # Zone 1: 10x10 图标网格
    for (col, row, name), rows in ICON_TEMPLATES.items():
        if len(rows) != 10 or any(len(r) != 10 for r in rows):
            raise ValueError(f"Icon {name} must be 10x10")
        u, v = col * 10, row * 10
        put_template(atlas, u, v, rows)
        boxes.append((name, u, v, 10, 10))

    # Zone 2: 状态角标与 16x16 专属图标
    draw_status_badges(atlas)
    for idx, label in enumerate(("STATUS_UNCOMPUTED", "STATUS_PENDING", "STATUS_CACHED", "STATUS_FAILED")):
        boxes.append((label, 128 + idx * 12, 0, 12, 12))

    draw_unknown_item(atlas, 180, 0)
    boxes.append(("UNKNOWN_ITEM", 180, 0, 16, 16))
    draw_cat_favor_hud(atlas, 196, 0)
    boxes.append(("CAT_FAVOR_HUD", 196, 0, 16, 16))
    draw_external_logos(atlas)
    boxes.extend([
        ("EXTERNAL_GITHUB", 128, 18, 16, 16),
        ("EXTERNAL_CURSEFORGE", 144, 18, 16, 16),
        ("EXTERNAL_MODRINTH", 160, 18, 16, 16),
    ])

    # Zone 3: 翻页箭头与侧签底板
    draw_page_arrows(atlas)
    boxes.extend([
        ("PAGE_PREV_N", 0, 52, 23, 13), ("PAGE_NEXT_N", 23, 52, 23, 13),
        ("PAGE_PREV_H", 0, 65, 23, 13), ("PAGE_NEXT_H", 23, 65, 23, 13),
        ("PAGE_PREV_D", 0, 78, 23, 13), ("PAGE_NEXT_D", 23, 78, 23, 13),
    ])
    draw_side_tabs(atlas)
    boxes.extend([
        ("TAB_MGMT_N", 50, 52, 24, 20), ("TAB_MGMT_H", 74, 52, 24, 20),
        ("TAB_HELP_N", 50, 72, 24, 20), ("TAB_HELP_H", 74, 72, 24, 20),
    ])

    # Zone 4: 9-Slice 按钮与面板皮肤
    nine_slice_defs = [
        ("BTN_PARCHMENT_NORMAL",   104, 52, (140, 111, 71, 255),  (243, 231, 200, 255), (255, 246, 223, 255), (222, 202, 161, 255), False),
        ("BTN_PARCHMENT_HOVER",    116, 52, (110, 75, 40, 255),   (250, 238, 206, 255), (255, 249, 230, 255), (226, 204, 152, 255), False),
        ("BTN_PARCHMENT_PRESSED",  128, 52, (94, 64, 34, 255),    (217, 192, 147, 255), (234, 215, 174, 255), (181, 151, 105, 255), True),
        ("BTN_PARCHMENT_SELECTED", 140, 52, (145, 100, 34, 255),  (234, 210, 159, 255), (252, 236, 188, 255), (206, 174, 114, 255), False),
        ("BTN_PARCHMENT_DISABLED", 152, 52, (191, 169, 134, 220), (236, 227, 204, 220), (244, 237, 218, 220), (220, 208, 180, 220), False),
        ("BTN_ACCENT_NORMAL",      104, 64, (109, 73, 34, 255),   (223, 196, 140, 255), (246, 226, 172, 255), (194, 164, 104, 255), False),
        ("BTN_ACCENT_HOVER",       116, 64, (86, 53, 23, 255),    (237, 210, 151, 255), (255, 240, 190, 255), (208, 176, 112, 255), False),
        ("BTN_ACCENT_PRESSED",     128, 64, (74, 46, 20, 255),    (201, 170, 111, 255), (226, 198, 138, 255), (166, 134, 78, 255),  True),
        ("BTN_ACCENT_DISABLED",    140, 64, (178, 159, 124, 220), (230, 218, 192, 220), (238, 229, 208, 220), (210, 196, 166, 220), False),
        ("BTN_QUIET_NORMAL",       104, 76, (170, 142, 100, 180), (238, 224, 190, 145), (252, 242, 216, 175), (216, 196, 156, 160), False),
    ]
    for name, u, v, border, fill, top_b, bot_b, sunk in nine_slice_defs:
        draw_nine_slice_button(atlas, u, v, border, fill, top_b, bot_b, sunk)
        boxes.append((name, u, v, 12, 12))

    draw_nine_slice_panel(atlas, 164, 52)
    boxes.append(("SCENARIO_PANEL", 164, 52, 12, 12))

    assert_non_overlapping(boxes)
    return atlas, boxes


def save_preview(atlas: Image.Image, boxes: list[tuple[str, int, int, int, int]], out_path: Path) -> None:
    """生成 4x 最近邻放大图，并在下方附加羊皮纸与暗色背景模拟对比条。"""
    scale = 4
    w, h = atlas.width * scale, atlas.height * scale
    preview = Image.new("RGBA", (w, h + 160), (236, 224, 194, 255))
    draw = ImageDraw.Draw(preview)
    for cy in range(0, h, scale * 2):
        for cx in range(0, w, scale * 2):
            c = (224, 210, 178, 255) if ((cx // (scale * 2)) + (cy // (scale * 2))) % 2 == 0 else (238, 226, 196, 255)
            draw.rectangle((cx, cy, min(cx + scale * 2 - 1, w - 1), min(cy + scale * 2 - 1, h - 1)), fill=c)
    up = atlas.resize((w, h), Image.Resampling.NEAREST)
    preview.paste(up, (0, 0), up)
    for name, u, v, bw, bh in boxes:
        outline = (180, 60, 60, 110) if bw == 10 else (40, 110, 180, 110)
        draw.rectangle((u * scale, v * scale, (u + bw) * scale - 1, (v + bh) * scale - 1), outline=outline)

    # 暗底模拟条（验证 PotteryWheel 与 LootTableManagement 图标可见性）
    dark_strip_y = h + 12
    draw.rectangle((0, dark_strip_y, w, h + 150), fill=(46, 48, 54, 255))
    dark_checks = [b for b in boxes if b[0].startswith(("DARK_", "POTTERY_", "CAT_"))]
    dx = 16
    for name, u, v, bw, bh in dark_checks:
        crop = atlas.crop((u, v, u + bw, v + bh)).resize((bw * 4, bh * 4), Image.Resampling.NEAREST)
        preview.paste(crop, (dx, dark_strip_y + 18), crop)
        draw.text((dx, dark_strip_y + 92), name.replace("DARK_", "").replace("POTTERY_", "PW_"), fill=(230, 230, 230, 255))
        dx += bw * 4 + 34

    out_path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(out_path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[2]
        / "common/src/main/resources/assets/unsuspiciousblock/textures/gui/ui_atlas.png",
        help="输出图集路径",
    )
    parser.add_argument("--preview", type=Path, default=None, help="可选：输出 4x 审校预览图路径")
    args = parser.parse_args()

    atlas, boxes = generate_atlas()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(args.output, optimize=True)
    print(f"Saved: {args.output} ({atlas.width}x{atlas.height}, {len(boxes)} regions)")

    if args.preview is not None:
        save_preview(atlas, boxes, args.preview)
        print(f"Saved preview: {args.preview}")


if __name__ == "__main__":
    main()
