"""生成全模组统一 UI 图标与控件皮肤图集 (textures/gui/ui_atlas.png, 256x128 RGBA)。"""
from __future__ import annotations

from drawlib import AtlasBuilder, Palette, PixelCanvas, RenderResult, TextureSpec, run_texture_cli

ATLAS_W = 256
ATLAS_H = 128

# —— 调色板：羊皮纸暖棕墨水 + 铜金/自然语义点缀 + 暗底高对比专色 ——
BASE_PALETTE = Palette({
    # 羊皮纸墨色系
    'D': (74, 50, 31, 255),      # 深橡木轮廓 (#4A321F)
    'I': (94, 64, 38, 255),      # 主墨线 (#5E4026)
    'L': (142, 106, 68, 255),    # 浅棕着色/副线 (#8E6A44)
    'W': (178, 132, 82, 255),    # 木纹/浅暖填充
    'S': (224, 198, 138, 255),   # 沙漏流沙/浅纸面
    'C': (245, 231, 190, 255),   # 浅米高光/助读反白 (#F5E7BE)
    'K': (255, 246, 223, 255),   # 极浅高光 (#FFF6DF)
    # 金属/铜金/收藏
    'g': (145, 100, 34, 255),    # 暗铜金
    'G': (206, 156, 54, 255),    # 琥珀金
    'Y': (244, 206, 102, 255),   # 亮金星芒
    # 镜片/群系/状态色
    'a': (150, 194, 208, 255),   # 放大镜玻璃柔青
    'A': (212, 236, 244, 255),   # 放大镜反光
    'e': (46, 106, 44, 255),     # 深森绿（描边）
    'E': (74, 154, 70, 255),     # 苍翠绿
    'h': (132, 212, 114, 255),   # 亮嫩绿
    'O': (79, 96, 51, 255),      # 橄榄子表绿 (与 CatalogPanel CHILD_COLOR 同色)
    'o': (118, 140, 78, 255),    # 浅橄榄绿
    'r': (134, 48, 36, 255),     # 暗朱红
    'R': (192, 76, 54, 255),     # 暖朱红
    'P': (138, 92, 188, 255),    # 维度幻紫
    'p': (176, 136, 222, 255),   # 维度浅紫
    'B': (123, 62, 24, 255),     # 笔记红棕 (#7B3E18)
    'b': (224, 144, 64, 255),    # 笔记悬停暖橙 (#E09040)
    # 暗底高对比（管理界面 & 陶轮台 & 帮扶反白）
    'd': (36, 38, 44, 255),      # 暗轮廓（陶轮暗石按钮护边）
    's': (156, 162, 174, 255),   # 银灰副色
    'V': (232, 236, 242, 255),   # 暗底高对比冷白 (#E8ECF2)
    'm': (110, 114, 126, 255),   # 暗灰环
    'M': (120, 214, 106, 255),   # 管理屏跟踪绿 (#78D66A)
    'Z': (244, 76, 76, 255),     # 红石亮红
    'z': (146, 26, 26, 255),     # 红石暗红
    'T': (242, 226, 190, 255),   # 复选框纸底
    # 16x16 外部/特殊图标专用色
    '1': (44, 34, 30, 255),      # GitHub 暖炭黑
    '2': (82, 68, 62, 255),      # GitHub 高光
    '3': (152, 52, 22, 255),     # CurseForge 暗边
    '4': (234, 94, 44, 255),     # CurseForge 橙焰
    '5': (255, 166, 98, 255),    # CurseForge 高光
    '6': (18, 148, 72, 255),     # Modrinth 暗绿
    '7': (32, 214, 106, 255),    # Modrinth 翡翠绿
    '8': (128, 244, 170, 255),   # Modrinth 高光
})

# Zone 1: 10x10 图标网格 (col, row, name)
ICON_TEMPLATES: dict[tuple[int, int, str], tuple[str, ...]] = {
    # —— Row 0 (v=0): 目录 & 排序/显隐 ——
    (0, 0, 'SEARCH'): (
        '..DDDD....',
        '.DAAAAI...',
        'DAAaaAID..',
        'DAaaLAID..',
        'DILLIIID..',
        '.DIIIID...',
        '.....DWD..',
        '......DWD.',
        '.......DWD',
        '........DD',
    ),
    (1, 0, 'CLOSE'): (
        '..........',
        '.DL....LD.',
        '.DID..DID.',
        '..DIDDID..',
        '...DIID...',
        '...DIID...',
        '..DIDDID..',
        '.DID..DID.',
        '.DL....LD.',
        '..........',
    ),
    (2, 0, 'ARROW_UP'): (
        '....DD....',
        '...DYYD...',
        '..DGIIGD..',
        '.DDILLIDD.',
        '...DIID...',
        '...DIID...',
        '...DIID...',
        '...DIID...',
        '...DIID...',
        '....DD....',
    ),
    (3, 0, 'ARROW_DOWN'): (
        '....DD....',
        '...DIID...',
        '...DIID...',
        '...DIID...',
        '...DIID...',
        '.DDILLIDD.',
        '..DGIIGD..',
        '...DYYD...',
        '....DD....',
        '..........',
    ),
    (4, 0, 'SHOW_LOCKED'): (
        '..........',
        '...DDDD...',
        '.DDKKKKDD.',
        'DKIDKIDKKD',
        'DKIDDDDKKD',
        '.DKIDDIKD.',
        '..DDDDDD..',
        '..........',
        '..........',
        '..........',
    ),
    (5, 0, 'HIDE_LOCKED'): (
        '........D.',
        '...DDDDID.',
        '.DDKKKDKD.',
        'DKIDKIDD..',
        'DKIDDIKD..',
        '.DKIDDDKD.',
        '.DIDDDD...',
        'DID.......',
        'D.........',
        '..........',
    ),
    (6, 0, 'SORT_DEFAULT'): (
        '..........',
        '.DD.DDDDD.',
        '.DI.ILLLL.',
        '..........',
        '.DD.DDDDD.',
        '.DI.ILLLL.',
        '..........',
        '.DD.DDDDD.',
        '.DI.ILLLL.',
        '..........',
    ),
    (7, 0, 'SORT_NAME'): (
        '...DDDD...',
        '..DGGGGD..',
        '.DGI..IGD.',
        '.DI....ID.',
        '.DIIIIIIID',
        '.DIIIIIIID',
        '.DI....ID.',
        '.DI....ID.',
        '.DD....DD.',
        '..........',
    ),
    (8, 0, 'SORT_ITEM_COUNT'): (
        '..........',
        '..DD..DD..',
        '..DI..DI..',
        '.DDDDDDDD.',
        '..DI..DI..',
        '..DI..DI..',
        '.DDDDDDDD.',
        '..DI..DI..',
        '..DD..DD..',
        '..........',
    ),
    (9, 0, 'SORT_FAVORITE'): (
        '....DD....',
        '...DYYD...',
        '..DGGGGD..',
        'DDYYGGGGDD',
        'DYGGGGGGGD',
        '.DGGGGGGD.',
        '..DGGGGD..',
        '.DGGDDGGD.',
        '.DGD..DGD.',
        '.DD....DD.',
    ),
    (10, 0, 'SORT_UNLOCK'): (
        '...DDDD...',
        '..DGGGGD..',
        '..DG..DD..',
        '..DG......',
        '.DDDDDDDD.',
        '.DGGGGGGD.',
        '.DGGDDGGD.',
        '.DGGDDGGD.',
        '.DDDDDDDD.',
        '..........',
    ),
    (11, 0, 'SORT_UPDATE_TIME'): (
        '.DDDDDDDD.',
        '.DWWWWWWd.',
        '..DIYYID..',
        '...DIID...',
        '....DD....',
        '...DYYD...',
        '..DYYYYD..',
        '.DWWWWWWd.',
        '.DDDDDDDD.',
        '..........',
    ),
    # —— Row 1 (v=10): 导航、状态、工具与日志动作 ——
    (0, 1, 'BACK'): (
        '..........',
        '...DD.....',
        '..DYYD....',
        '.DYGGDDDD.',
        'DYGIIIIIGD',
        '.DGGGGGGGD',
        '..DGGDDDD.',
        '...DD.....',
        '..........',
        '..........',
    ),
    (1, 1, 'ENABLED'): (
        '..........',
        '........e.',
        '.......eEe',
        '......ehED',
        '.e...ehED.',
        'eEe.ehED..',
        '.ehEhED...',
        '..ehED....',
        '...eD.....',
        '..........',
    ),
    (2, 1, 'DISABLED'): (
        '...DDDD...',
        '..DLLLLD..',
        '..DL..LD..',
        '..DL..LD..',
        '.DDDDDDDD.',
        '.DWWDDWWD.',
        '.DWWD.WWD.',
        '.DWWDDWWD.',
        '.DDDDDDDD.',
        '..........',
    ),
    (3, 1, 'REDSTONE'): (
        '....rr....',
        '...rZZr...',
        '..rZCKZr..',
        '.rZZZZZZr.',
        '.rZZzzZZr.',
        'rZZzZZzZZr',
        '.rZZZZZZr.',
        '..rrrrrr..',
        '..........',
        '..........',
    ),
    (4, 1, 'GEAR'): (
        '...D..D...',
        '.D.DIID.D.',
        'DDGIYYIGDD',
        '.DGIDDIGD.',
        'DGI....IGD',
        'DGI....IGD',
        '.DGIDDIGD.',
        'DDGIIIIGDD',
        '.D.DIID.D.',
        '...D..D...',
    ),
    (5, 1, 'HELP'): (
        '..DDDDDD..',
        '.DGGGGGGD.',
        '.DG....GD.',
        '.....DGGD.',
        '....DGGD..',
        '....DGD...',
        '....DD....',
        '..........',
        '....DD....',
        '....DD....',
    ),
    (6, 1, 'PENCIL'): (
        '......DD..',
        '.....DRRD.',
        '....DWWWRD',
        '...DGGGGD.',
        '..DGGGGD..',
        '.DGGGGD...',
        '.DCCCID...',
        '.DCCID....',
        '.DDID.....',
        '.DDD......',
    ),
    (7, 1, 'CLOCK'): (
        '....DD....',
        '..DDDDDD..',
        '.DCCCCCCd.',
        'DCCCDCCCCD',
        'DCCCDCCCCD',
        'DCCCDDDCCD',
        'DCCCCCCCCD',
        '.DCCCCCCd.',
        '..DDDDDD..',
        '..........',
    ),
    (8, 1, 'BIOME'): (
        '....ee....',
        '..eehhee..',
        '.ehEEEEhe.',
        '.eEEEEEEe.',
        '..eEEEEe..',
        '...DDDD...',
        '....DI....',
        '....DI....',
        '..eeDDee..',
        '..........',
    ),
    (9, 1, 'DIMENSION'): (
        '....DD....',
        '..DDPPDD..',
        '.DPpPPPpD.',
        '.DPPPPPPD.',
        '.DPPpPpPD.',
        '.DPPPPPPD.',
        '.DPpPPPpD.',
        '..DDPPDD..',
        '....DD....',
        '..........',
    ),
    (10, 1, 'TRASH_EMPTY'): (
        '...DDDD...',
        '.DDDDDDDD.',
        '.DKGGGGKD.',
        '..DWWWWd..',
        '..DWDDWd..',
        '..DWDDWd..',
        '..DWDDWd..',
        '...DWWD...',
        '...DDDD...',
        '..........',
    ),
    (11, 1, 'TRASH_FULL'): (
        '.DDKKKKD..',
        '.DYKCCRRD.',
        '..DDDDDDD.',
        '..DWWWWd..',
        '..DWDDWd..',
        '..DWDDWd..',
        '..DWDDWd..',
        '...DWWD...',
        '...DDDD...',
        '..........',
    ),
    # —— Row 2 (v=20): 批量操作、复制与场景按钮 ——
    (0, 2, 'SQUARE'): (
        '..........',
        '.DDDDDDDD.',
        '.DCCCCCCd.',
        '.DC....CD.',
        '.DC....CD.',
        '.DC....CD.',
        '.DC....CD.',
        '.DCCCCCCd.',
        '.DDDDDDDD.',
        '..........',
    ),
    (1, 2, 'GROUP'): (
        '..........',
        '.DDD..DDD.',
        '.DYD..DCD.',
        '.DDD..DDD.',
        '..........',
        '.DDD..DDD.',
        '.DCD..DYD.',
        '.DDD..DDD.',
        '..........',
        '..........',
    ),
    # COPY 故意留出第 0 行与第 9 行透明，保证 14x10 按钮内无边缘碰撞
    (2, 2, 'COPY'): (
        '..........',
        '.DDDDD....',
        '.DKKKDDDD.',
        '.DK.DKLLD.',
        '.DK.DKKKD.',
        '.DD.DKLLD.',
        '....DKKKD.',
        '....DCCCD.',
        '....DDDDD.',
        '..........',
    ),
    (3, 2, 'EXPAND'): (
        '.DDDD.DDDD',
        '.DYYD.DYYD',
        '.DYID.DIYD',
        '.DD.D.D.DD',
        '..........',
        '..........',
        '.DD.D.D.DD',
        '.DYID.DIYD',
        '.DYYD.DYYD',
        '.DDDD.DDDD',
    ),
    (4, 2, 'FIT'): (
        '.DD....DD.',
        '.DID..DID.',
        'DDDD..DDDD',
        '..........',
        '....DD....',
        '....DD....',
        '..........',
        'DDDD..DDDD',
        '.DID..DID.',
        '.DD....DD.',
    ),
    (5, 2, 'PLUS'): (
        '..........',
        '....DD....',
        '....DI....',
        '....DI....',
        '.DDDIIDDD.',
        '.DIIIIILD.',
        '....DI....',
        '....DI....',
        '....DD....',
        '..........',
    ),
    (6, 2, 'MINUS'): (
        '..........',
        '..........',
        '..........',
        '..........',
        '.DDDDDDDD.',
        '.DIIIIILD.',
        '..........',
        '..........',
        '..........',
        '..........',
    ),
    (7, 2, 'PARAMS'): (
        '..........',
        '..DDD.....',
        '.DYGYLLLL.',
        '..DDD.....',
        '..........',
        '.....DDD..',
        '.LLLLGYGD.',
        '.....DDD..',
        '..........',
        '..........',
    ),
    (8, 2, 'CALCULATE'): (
        '..........',
        '..DD......',
        '..DYD.....',
        '..DYGD....',
        '..DYGGD...',
        '..DYGGD...',
        '..DYGD....',
        '..DYD.....',
        '..DD......',
        '..........',
    ),
    (9, 2, 'RECOMMEND'): (
        '....DD....',
        '...DYYD.Y.',
        '...DYGDYKY',
        '.DDYKKYDY.',
        'DYYKGGKYD.',
        '.DDYGGYDD.',
        '...DGGD...',
        '...DYYD...',
        '....DD....',
        '..........',
    ),
    (10, 2, 'CHEVRON_DOWN'): (
        '..........',
        '..........',
        '.DD....DD.',
        '.DYD..DYD.',
        '..DYDDYD..',
        '...DGGD...',
        '....DD....',
        '..........',
        '..........',
        '..........',
    ),
    (11, 2, 'CHEVRON_UP'): (
        '..........',
        '....DD....',
        '...DGGD...',
        '..DYDDYD..',
        '.DYD..DYD.',
        '.DD....DD.',
        '..........',
        '..........',
        '..........',
        '..........',
    ),
    # —— Row 3 (v=30): 目录树/列表行内标识、复选框、侧签与暗底树 ——
    (0, 3, 'TREE_COLLAPSED'): (
        '..........',
        '..DD......',
        '..DYD.....',
        '..DIGD....',
        '..DIGGD...',
        '..DIGD....',
        '..DYD.....',
        '..DD......',
        '..........',
        '..........',
    ),
    (1, 3, 'TREE_EXPANDED'): (
        '..........',
        '..........',
        '.DDDDDDDD.',
        '.DYYYYYYD.',
        '..DGGGGD..',
        '...DIID...',
        '....DD....',
        '..........',
        '..........',
        '..........',
    ),
    (2, 3, 'FAVORITE_BADGE'): (
        '....gg....',
        '...gYYg...',
        '.ggYGGYgg.',
        'gYYGGGGYYg',
        '.gGGGGGGg.',
        '..gGGGGg..',
        '.gGGggGGg.',
        '.gGg..gGg.',
        '..........',
        '..........',
    ),
    (3, 3, 'CHILD_BADGE'): (
        '..........',
        '.OO.......',
        '.Oo.......',
        '.Oo.OOOOO.',
        '.Oo.OoooO.',
        '.OoooOoOO.',
        '..OO.OOOO.',
        '..........',
        '..........',
        '..........',
    ),
    (4, 3, 'NOTE_BADGE'): (
        '..........',
        '.......BB.',
        '......BRB.',
        '.....BWWD.',
        '....BWWB..',
        '...BWWB...',
        '..BCCB....',
        '..BBB.....',
        '..........',
        '..........',
    ),
    (5, 3, 'NOTE_BADGE_HOVER'): (
        '..........',
        '.......bb.',
        '......bYb.',
        '.....bYYb.',
        '....bYYb..',
        '...bYYb...',
        '..bCCb....',
        '..bbb.....',
        '..........',
        '..........',
    ),
    (6, 3, 'CHECKBOX_UNCHECKED'): (
        '..........',
        '.DDDDDDDD.',
        '.DLLLLLLC.',
        '.DLTTTTTC.',
        '.DLTTTTTC.',
        '.DLTTTTTC.',
        '.DLTTTTTC.',
        '.DCCCCCCC.',
        '.DDDDDDDD.',
        '..........',
    ),
    (7, 3, 'CHECKBOX_CHECKED'): (
        '.........e',
        '.DDDDDDDeh',
        '.DLLLLLeEe',
        '.DLTTTeED.',
        '.DeTTeED..',
        '.DeEeED...',
        '.DLeeTD...',
        '.DCCCCCCC.',
        '.DDDDDDDD.',
        '..........',
    ),
    (8, 3, 'BRANCH_ARROW'): (
        '..........',
        '.DD.......',
        '.DL...DD..',
        '.DL...DGD.',
        '.DLLLLDGGD',
        '..DDDDDGD.',
        '......DD..',
        '..........',
        '..........',
        '..........',
    ),
    (9, 3, 'HELP_CREAM'): (
        '..KKKKKK..',
        '.KCCCCCCK.',
        '.KC....CK.',
        '.....KCCK.',
        '....KCCK..',
        '....KCK...',
        '....KK....',
        '..........',
        '....KK....',
        '....KK....',
    ),
    (10, 3, 'DARK_TREE_COLLAPSED'): (
        '..........',
        '..dd......',
        '..dVd.....',
        '..dVsVd...',
        '..dVssVd..',
        '..dVsVd...',
        '..dVd.....',
        '..dd......',
        '..........',
        '..........',
    ),
    (11, 3, 'DARK_TREE_EXPANDED'): (
        '..........',
        '..........',
        '.dddddddd.',
        '.dVVVVVVd.',
        '..dssssd..',
        '...dssdd..',
        '....dd....',
        '..........',
        '..........',
        '..........',
    ),
    # —— Row 4 (v=40): 管理屏追踪状态 & 陶轮暗底高对比专槽 ——
    (0, 4, 'DARK_TRACKED'): (
        '..........',
        '...eeee...',
        '..ehhhhe..',
        '.ehMMMMhe.',
        '.eMMMMMMe.',
        '.eMMMMMMe.',
        '..eMMMMe..',
        '...eeee...',
        '..........',
        '..........',
    ),
    (1, 4, 'DARK_UNTRACKED'): (
        '..........',
        '...dddd...',
        '..dmmmm...',
        '.dm....md.',
        '.dm....md.',
        '.dm....md.',
        '..dmmmmd..',
        '...dddd...',
        '..........',
        '..........',
    ),
    (2, 4, 'POTTERY_ENABLED'): (
        '..........',
        '........d.',
        '.......dhd',
        '......dMhd',
        '.d...dMEd.',
        'dhd.dMEd..',
        '.dMhMEd...',
        '..dMEd....',
        '...dd.....',
        '..........',
    ),
    (3, 4, 'POTTERY_DISABLED'): (
        '...dddd...',
        '..dVVVVd..',
        '..dV..Vd..',
        '..dV..Vd..',
        '.dddddddd.',
        '.dRsddRsd.',
        '.dRsd.Rsd.',
        '.dRsddRsd.',
        '.dddddddd.',
        '..........',
    ),
    (4, 4, 'POTTERY_REDSTONE'): (
        '....dd....',
        '...dZZd...',
        '..dZKKZd..',
        '.dZZZZZZd.',
        '.dZZzzZZd.',
        'dZZzZZzZZd',
        '.dZZzzZZd.',
        '..dzzzzd..',
        '...dddd...',
        '..........',
    ),
}

CAT_FAVOR_PALETTE = Palette({'r': (128, 128, 128, 255), 'p': (206, 206, 206, 255), 'W': (255, 255, 255, 255)})
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


def _paint_side_tab(c: PixelCanvas, pal: dict[str, str]) -> None:
    c.polygon(SIDE_TAB_OUTER, pal['outline'])
    c.polygon(SIDE_TAB_INNER, pal['fill'])
    c.hline(4, 2, 19, pal['highlight'])
    c.vline(2, 3, 13, pal['highlight'])
    c.hline(4, 17, 19, pal['shadow'])
    c.vline(22, 3, 15, pal['shadow'])


def render() -> RenderResult:
    """绘制全模组统一 UI 图集及全部区域注册清单。"""
    atlas = AtlasBuilder(ATLAS_W, ATLAS_H)

    # Zone 1: 10x10 图标网格
    for (col, row, name), rows in ICON_TEMPLATES.items():
        atlas.place_grid_ascii(col, row, 10, 10, name, rows, BASE_PALETTE)

    # Zone 2: 状态角标与 16x16 专属图标
    badge_specs = (
        ('STATUS_UNCOMPUTED', (244, 232, 202, 255), (255, 246, 224, 255), 'uncomputed'),
        ('STATUS_PENDING',    (248, 236, 198, 255), (255, 248, 222, 255), 'pending'),
        ('STATUS_CACHED',     (232, 244, 222, 255), (246, 254, 238, 255), 'cached'),
        ('STATUS_FAILED',     (248, 228, 222, 255), (255, 242, 236, 255), 'failed'),
    )
    for idx, (label, bg, hi, kind) in enumerate(badge_specs):
        atlas.paint(label, 128 + idx * 12, 0, 12, 12, lambda c, b=bg, h=hi, k=kind: _paint_status_badge(c, b, h, k))

    atlas.paint('UNKNOWN_ITEM', 180, 0, 16, 16, _paint_unknown_item)
    atlas.place_ascii('CAT_FAVOR_HUD', 196, 0, CAT_FAVOR_GRID, CAT_FAVOR_PALETTE, expected_size=(16, 16))
    for name, u, grid in EXTERNAL_LOGOS:
        atlas.place_ascii(name, u, 18, grid, BASE_PALETTE, expected_size=(16, 16))

    # Zone 3: 翻页箭头与侧签底板
    for tag, v, pal in PAGE_ARROW_STATES:
        atlas.place_ascii(f'PAGE_PREV_{tag}', 0, v, PAGE_ARROW_SHAPE, pal, expected_size=(23, 13), mirror_x=True)
        atlas.place_ascii(f'PAGE_NEXT_{tag}', 23, v, PAGE_ARROW_SHAPE, pal, expected_size=(23, 13))
    for name, u, v, pal in SIDE_TABS:
        atlas.paint(name, u, v, 24, 20, lambda c, p=pal: _paint_side_tab(c, p))

    # Zone 4: 9-Slice 按钮与面板皮肤
    for name, u, v, border, fill, top_b, bot_b, sunk in NINE_SLICE_BUTTONS:
        atlas.paint(
            name, u, v, 12, 12,
            lambda c, br=border, fl=fill, hi=bot_b if sunk else top_b, sh=top_b if sunk else bot_b: c.chamfer_panel(
                0, 0, 12, 12, border=br, fill=fl, highlight=hi, shade=sh, highlight_width=1, shade_width=1
            ),
        )
    atlas.paint(
        'SCENARIO_PANEL', 164, 52, 12, 12,
        lambda c: c.bevel_rect(0, 0, 12, 12, border='#b49b73', fill='#f2e5c6', top_left='#fff4d9', bottom='#dfcca4'),
    )
    return atlas.build()


SPEC = TextureSpec(
    name='ui_atlas',
    description=__doc__ or '生成全模组统一 UI 图标与控件皮肤图集',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/gui/ui_atlas.png',
    render=render,
    default_preview_scale=4,
    dark_strip_prefixes=('DARK_', 'POTTERY_', 'CAT_'),
)

if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
