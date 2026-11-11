"""全 UI 图集共享语义色板与按来源分块预览工具。"""
from __future__ import annotations

import argparse
from collections.abc import Callable, Sequence
from pathlib import Path
import sys
from PIL import Image, ImageDraw

_DRAWER_ROOT = Path(__file__).resolve().parents[1]
if str(_DRAWER_ROOT) not in sys.path:
    sys.path.insert(0, str(_DRAWER_ROOT))

from drawlib import AtlasBuilder, Palette, Region, parse_color

ATLAS_W = 256
ATLAS_H = 128

# 全图集统一语义色板：
# 浅底图标优先使用主深棕墨 'I' 保持干净剪影，仅在存在层次区隔时使用少量平涂辅助色。
UI_PALETTE = Palette({
    # 羊皮纸墨色与纸底（低噪声核心组）
    'I': '#4E3420',  # 主深橡木墨（与 #F3E7C8 保持高对比）
    'L': '#8C6540',  # 次暖褐（折叠后页 / 次要结构）
    'W': '#B58450',  # 暖木/皮革辅助填色
    'S': '#E4C888',  # 浅金沙填色
    'T': '#FBF0D6',  # 纸面净底（复选框/白纸/表盘）
    'C': '#EFE0B8',  # 柔和浅米
    'K': '#FFF8E4',  # 极浅高光 / 帮扶反白
    # 铜金与星辉
    'g': '#A86D1C',  # 铜金收边/暗金
    'G': '#D49830',  # 琥珀金主色
    'Y': '#F4CA60',  # 亮金点缀
    # 镜片 / 生态 / 语义专色
    'a': '#9CCEE0',  # 放大镜清青
    'A': '#D8F0F8',  # 放大镜高光
    'e': '#2F6E2D',  # 深森绿
    'E': '#489E44',  # 纯正森绿
    'h': '#7BD06C',  # 亮嫩绿
    'O': '#4F6033',  # 橄榄子表绿（对齐 CatalogPanel CHILD_COLOR）
    'o': '#7E9852',  # 浅橄榄绿
    'r': '#9A2820',  # 暗朱红
    'R': '#D64234',  # 纯正红
    'z': '#F27868',  # 暖亮红
    'P': '#6D44A8',  # 维度深紫
    'p': '#A278DC',  # 维度亮紫
    'B': '#7B3E18',  # 笔记红棕
    'b': '#E69438',  # 笔记悬停暖橙
    # 暗底高对比专色（管理界面 / 陶轮 / 暗灰按钮）
    'V': '#E8ECF2',  # 冷白主色
    's': '#9CA4B4',  # 银灰辅助
    'd': '#363942',  # 暗炭灰（暗底孔洞/外缘）
    'm': '#7C8292',  # 暗环中灰
    'M': '#72DA64',  # 追踪/启用亮绿
    'Z': '#F24949',  # 红石亮红
    'u': '#FF9898',  # 红石反光粉红
    # 16x16 外链品牌专色
    '1': (44, 34, 30, 255),
    '2': (82, 68, 62, 255),
    '3': (152, 52, 22, 255),
    '4': (234, 94, 44, 255),
    '5': (255, 166, 98, 255),
    '6': (18, 148, 72, 255),
    '7': (32, 214, 106, 255),
    '8': (128, 244, 170, 255),
})

CAT_FAVOR_PALETTE = Palette({'r': (128, 128, 128, 255), 'p': (206, 206, 206, 255), 'W': (255, 255, 255, 255)})


def render_source_sheet(
    regions: Sequence[Region],
    source: Image.Image,
    *,
    bg: str = '#F3E7C8',
    scale: int = 6,
    cols: int = 8,
) -> Image.Image:
    """将一组图集区域按真实底色平铺放大，便于单独打磨某个来源的图标。"""
    if not regions:
        return Image.new('RGBA', (64, 64), parse_color(bg))
    pad = 12
    label_h = 16
    cell_w = max(r.width * scale for r in regions) + 28
    cell_h = max(r.height * scale for r in regions) + label_h + 12
    rows = (len(regions) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cell_w + pad * 2, rows * cell_h + pad * 2), parse_color(bg))
    draw = ImageDraw.Draw(sheet)
    dark_bg = bg in ('#2E3036', '#404040')
    text_color = (232, 236, 242, 255) if dark_bg else (78, 52, 32, 255)
    frame_color = (90, 96, 110, 120) if dark_bg else (170, 140, 100, 120)

    for idx, region in enumerate(regions):
        cx = pad + (idx % cols) * cell_w + (cell_w - region.width * scale) // 2
        cy = pad + (idx // cols) * cell_h + 4
        crop = source.crop((region.x, region.y, region.x + region.width, region.y + region.height))
        zoom = crop.resize((region.width * scale, region.height * scale), Image.Resampling.NEAREST)
        draw.rectangle((cx - 1, cy - 1, cx + region.width * scale, cy + region.height * scale), outline=frame_color)
        sheet.paste(zoom, (cx, cy), zoom)
        short_name = region.name[:12]
        draw.text((pad + (idx % cols) * cell_w + 4, cy + region.height * scale + 3), short_name, fill=text_color)
    return sheet


def run_source_preview(
    source_name: str,
    applier: Callable[[AtlasBuilder], None],
    *,
    bg: str = '#F3E7C8',
) -> int:
    """独立运行某个来源子脚本，验证无冲突并按需导出该来源预览表。"""
    parser = argparse.ArgumentParser(description=f'单独调试并预览 UI 图集来源模块：{source_name}')
    parser.add_argument('--preview', type=Path, required=True, help='输出该来源的放大对照预览图路径')
    parser.add_argument('--scale', type=int, default=6, help='预览放大倍率（默认 6）')
    parser.add_argument('--bg', default=bg, help='预览背景色（默认按该来源主界面配色）')
    args = parser.parse_args()

    atlas = AtlasBuilder(ATLAS_W, ATLAS_H)
    applier(atlas)
    result = atlas.build()
    sheet = render_source_sheet(result.regions, result.image, bg=args.bg, scale=args.scale)
    args.preview.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.preview)
    print(f'[预览] {source_name}: {len(result.regions)} regions -> {args.preview}')
    return 0
