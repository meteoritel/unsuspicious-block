#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 12x12 像素置顶图钉图标
风格匹配 catalog_entry.png 的暖棕色调
"""

from pathlib import Path
from PIL import Image

# 项目根目录
ROOT = Path(__file__).resolve().parent.parent

# 输出路径：脚本目录下放置预览/源文件，游戏资源目录放置最终纹理
SCRIPT_OUTPUT = ROOT / "scripts" / "catalog_entry_pin.png"
TEXTURE_OUTPUT = (
    ROOT
    / "common"
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "unsuspiciousblock"
    / "textures"
    / "gui"
    / "catalog_entry_pin.png"
)

# 调色板 - 与 catalog_entry 的米色/棕色羊皮纸风格协调
TRANSPARENT = (0, 0, 0, 0)
OUTLINE = (74, 52, 37, 255)        # #4A3425 深棕描边
SHADOW = (125, 90, 60, 255)        # #7D5A3C 暗部
BASE = (196, 154, 108, 255)        # #C49A6C 主体
HIGHLIGHT = (232, 212, 168, 255)   # #E8D4A8 高光

# 12x12 像素图钉设计（尖端朝下，表示固定于条目顶部）
# '.' = 透明, 'O' = 描边, 'S' = 阴影, 'B' = 主体, 'H' = 高光
PIXEL_MAP = [
    "............",
    "....OOOO....",
    "...OHHBOO...",
    "..OHHBBBOO..",
    "..OHHBBBOO..",
    "...OBBBOO...",
    "....OBOO....",
    "...OBBBOO...",
    "..OBBBBBOO..",
    ".OBBBBBBBBO.",
    "OO........OO",
    "............",
]


def draw_icon(pixel_map: list[str]) -> Image.Image:
    """根据像素字符图绘制图标。"""
    size = 12
    img = Image.new("RGBA", (size, size), TRANSPARENT)
    pixels = img.load()

    char_to_color = {
        ".": TRANSPARENT,
        "O": OUTLINE,
        "S": SHADOW,
        "B": BASE,
        "H": HIGHLIGHT,
    }

    for y, row in enumerate(pixel_map):
        for x, char in enumerate(row):
            pixels[x, y] = char_to_color[char]

    return img


def main():
    icon = draw_icon(PIXEL_MAP)

    # 保存到 scripts 目录作为生成产物
    icon.save(SCRIPT_OUTPUT)
    print(f"已生成图标: {SCRIPT_OUTPUT}")

    # 同时保存到游戏资源目录，方便直接使用
    if TEXTURE_OUTPUT.parent.exists():
        icon.save(TEXTURE_OUTPUT)
        print(f"已同步到纹理目录: {TEXTURE_OUTPUT}")
    else:
        print(f"纹理目录不存在，跳过同步: {TEXTURE_OUTPUT}")


if __name__ == "__main__":
    main()
