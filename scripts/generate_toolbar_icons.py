"""生成考古手册工具栏图标 sprite sheet (toolbar_icons.png)

图标范围（共 14 个）：
- 搜索/返回：search_open, search_back
- 排序方向：sort_asc, sort_desc
- 目录排序方式：cat_default, cat_name, cat_unlock, cat_count
- 日志排序方式：log_time, log_struct, log_dim, log_source
- 隐藏未解锁开关：hide_off, hide_on

所有图标均为 14x14 像素，透明背景，深棕色调，仅绘制前景图形，
按钮外框由 IconButton 代码绘制。

布局（4 列 x 4 行，单元格 14x14，总图集 56x56）：
  行 0: search_open  search_back   sort_asc     sort_desc
  行 1: cat_default  cat_name      cat_unlock   cat_count
  行 2: log_time     log_struct    log_dim      log_source
  行 3: hide_off     hide_on       <空>         <空>

输出: common/src/main/resources/assets/unsuspiciousblock/textures/gui/toolbar_icons.png
"""

import os
from PIL import Image

# 颜色（与 IconButton.TEXT_COLOR_NORMAL=0xFF5A3D23 协调）
INK = (90, 61, 35, 255)              # 主线条
INK_LIGHT = (138, 94, 56, 255)       # 阴影/副线条
HIGHLIGHT = (212, 168, 96, 255)      # 高光
TRANS = (0, 0, 0, 0)

CELL = 14
COLS = 4
ROWS = 4
SHEET_W = CELL * COLS
SHEET_H = CELL * ROWS


# ── 像素绘制工具 ──

def px(img, x, y, color=INK):
    # 单像素绘制，越界保护
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), color)


def hline(img, x0, x1, y, color=INK):
    for x in range(x0, x1 + 1):
        px(img, x, y, color)


def vline(img, x, y0, y1, color=INK):
    for y in range(y0, y1 + 1):
        px(img, x, y, color)


def rect_outline(img, x0, y0, x1, y1, color=INK):
    hline(img, x0, x1, y0, color)
    hline(img, x0, x1, y1, color)
    vline(img, x0, y0, y1, color)
    vline(img, x1, y0, y1, color)


def fill_rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(img, x, y, color)


def new_cell():
    return Image.new("RGBA", (CELL, CELL), TRANS)


# ── 图标定义 ──

def icon_search_open(c):
    # 放大镜：圆环 + 斜柄
    ring = [
        (4, 2), (5, 2), (6, 2), (7, 2),
        (3, 3), (8, 3),
        (2, 4), (9, 4),
        (2, 5), (9, 5),
        (2, 6), (9, 6),
        (3, 7), (8, 7),
        (4, 8), (5, 8), (6, 8), (7, 8),
    ]
    for x, y in ring:
        px(c, x, y, INK)
    # 斜柄
    for i in range(4):
        px(c, 8 + i, 8 + i, INK)
        px(c, 9 + i, 8 + i, INK_LIGHT)
    # 镜片高光
    px(c, 4, 4, HIGHLIGHT)
    px(c, 5, 3, HIGHLIGHT)


def icon_search_back(c):
    # 返回箭头：← 厚体
    # 箭头主杆
    hline(c, 4, 11, 6, INK)
    hline(c, 4, 11, 7, INK)
    hline(c, 4, 11, 8, INK_LIGHT)
    # 箭头尖（左指）
    for k in range(0, 4):
        px(c, 3 + k, 3 + k, INK)
        px(c, 3 + k, 11 - k, INK)
        px(c, 4 + k, 3 + k, INK_LIGHT)
        px(c, 4 + k, 11 - k, INK_LIGHT)
    # 箭头尖端实心
    fill_rect(c, 2, 6, 3, 8, INK)


def icon_sort_asc(c):
    # 升序：左侧由短到长的横条 + 右侧上箭头
    hline(c, 1, 4, 3, INK); hline(c, 1, 4, 4, INK_LIGHT)
    hline(c, 1, 6, 6, INK); hline(c, 1, 6, 7, INK_LIGHT)
    hline(c, 1, 8, 9, INK); hline(c, 1, 8, 10, INK_LIGHT)
    # 右侧箭头（朝上）
    px(c, 11, 2, INK)
    hline(c, 10, 12, 3, INK)
    hline(c, 9, 13, 4, INK)
    vline(c, 11, 5, 11, INK)
    vline(c, 12, 5, 11, INK_LIGHT)


def icon_sort_desc(c):
    # 降序：左侧由长到短的横条 + 右侧下箭头
    hline(c, 1, 8, 3, INK); hline(c, 1, 8, 4, INK_LIGHT)
    hline(c, 1, 6, 6, INK); hline(c, 1, 6, 7, INK_LIGHT)
    hline(c, 1, 4, 9, INK); hline(c, 1, 4, 10, INK_LIGHT)
    # 右侧箭头（朝下）
    vline(c, 11, 2, 8, INK)
    vline(c, 12, 2, 8, INK_LIGHT)
    hline(c, 9, 13, 9, INK)
    hline(c, 10, 12, 10, INK)
    px(c, 11, 11, INK)


def icon_cat_default(c):
    # 默认排序：编号列表（左侧小圆点 + 右侧横条），传达"原始顺序"
    rows_y = [2, 6, 10]
    for y in rows_y:
        # 小圆点（2×2）
        fill_rect(c, 2, y, 3, y + 1, INK)
        # 横条
        hline(c, 5, 11, y, INK)
        hline(c, 5, 11, y + 1, INK_LIGHT)


def icon_cat_name(c):
    # 名称排序：左上方块 + 右下方块 + 斜线（Az 暗示）
    # 左上方块
    fill_rect(c, 2, 2, 5, 5, INK)
    fill_rect(c, 3, 3, 4, 4, INK_LIGHT)
    # 中间斜线
    for i in range(0, 7):
        px(c, 5 + i, 5 + i, INK)
        px(c, 6 + i, 5 + i, INK_LIGHT)
    # 右下方块
    fill_rect(c, 8, 8, 11, 11, INK)
    fill_rect(c, 9, 9, 10, 10, INK_LIGHT)


def icon_cat_unlock(c):
    # 解锁排序：钥匙
    # 钥匙环
    ring = [
        (3, 3), (4, 2), (5, 2), (6, 3),
        (2, 4), (7, 4),
        (2, 5), (7, 5),
        (2, 6), (7, 6),
        (3, 7), (4, 8), (5, 8), (6, 7),
    ]
    for x, y in ring:
        px(c, x, y, INK)
    # 环内孔
    fill_rect(c, 4, 4, 5, 6, INK_LIGHT)
    # 钥匙杆（从环右侧延伸到右下）
    hline(c, 7, 12, 5, INK)
    hline(c, 7, 12, 6, INK_LIGHT)
    # 齿（两个齿）
    vline(c, 9, 7, 8, INK)
    vline(c, 11, 7, 8, INK)


def icon_cat_count(c):
    # 数量排序：堆叠的三层方块
    # 后层（最浅、最高）
    rect_outline(c, 2, 2, 8, 5, INK)
    fill_rect(c, 3, 3, 7, 4, INK_LIGHT)
    # 中层
    rect_outline(c, 4, 5, 10, 8, INK)
    fill_rect(c, 5, 6, 9, 7, INK_LIGHT)
    # 前层（最低、最深）
    rect_outline(c, 6, 8, 12, 11, INK)
    fill_rect(c, 7, 9, 11, 10, INK_LIGHT)


def icon_log_time(c):
    # 时钟：圆环 + 指针
    ring = [
        (5, 1), (6, 1), (7, 1), (8, 1),
        (3, 2), (4, 2), (9, 2), (10, 2),
        (2, 3), (11, 3),
        (1, 4), (12, 4),
        (1, 5), (12, 5),
        (1, 6), (12, 6),
        (1, 7), (12, 7),
        (1, 8), (12, 8),
        (2, 9), (11, 9),
        (3, 10), (4, 10), (9, 10), (10, 10),
        (5, 11), (6, 11), (7, 11), (8, 11),
    ]
    for x, y in ring:
        px(c, x, y, INK)
    # 时针（向上）
    vline(c, 6, 3, 6, INK)
    vline(c, 7, 3, 6, INK_LIGHT)
    # 分针（向右）
    hline(c, 6, 9, 6, INK)
    hline(c, 6, 9, 7, INK_LIGHT)
    # 中心
    fill_rect(c, 6, 6, 7, 7, INK)


def icon_log_struct(c):
    # 神庙：三角顶 + 横梁 + 三柱 + 基座
    px(c, 6, 1, INK); px(c, 7, 1, INK)
    hline(c, 5, 8, 2, INK)
    hline(c, 4, 9, 3, INK)
    hline(c, 2, 11, 4, INK)
    hline(c, 2, 11, 5, INK_LIGHT)
    for cx in [3, 6, 9]:
        vline(c, cx, 6, 10, INK)
        vline(c, cx + 1, 6, 10, INK_LIGHT)
    hline(c, 1, 12, 11, INK)
    hline(c, 1, 12, 12, INK_LIGHT)


def icon_log_dim(c):
    # 罗盘：菱形外框 + 十字 + 北针
    diamond = [
        (6, 1), (7, 1),
        (5, 2), (8, 2),
        (4, 3), (9, 3),
        (3, 4), (10, 4),
        (2, 5), (11, 5),
        (1, 6), (12, 6),
        (2, 7), (11, 7),
        (3, 8), (10, 8),
        (4, 9), (9, 9),
        (5, 10), (8, 10),
        (6, 11), (7, 11),
    ]
    for x, y in diamond:
        px(c, x, y, INK)
    # 十字（淡色）
    vline(c, 6, 3, 9, INK_LIGHT)
    vline(c, 7, 3, 9, INK_LIGHT)
    hline(c, 3, 10, 6, INK_LIGHT)
    hline(c, 3, 10, 7, INK_LIGHT)
    # 中心
    fill_rect(c, 6, 6, 7, 7, INK)
    # 北针（向上加深）
    fill_rect(c, 6, 3, 7, 5, INK)


def icon_log_source(c):
    # 卷轴：上下卷边 + 中部文本横线
    # 上卷
    fill_rect(c, 1, 2, 12, 3, INK)
    hline(c, 2, 11, 4, INK_LIGHT)
    # 下卷
    fill_rect(c, 1, 10, 12, 11, INK)
    hline(c, 2, 11, 9, INK_LIGHT)
    # 中部两侧框
    vline(c, 1, 5, 8, INK)
    vline(c, 12, 5, 8, INK)
    # 文本三横
    hline(c, 3, 10, 6, INK)
    hline(c, 3, 9, 8, INK)
    hline(c, 3, 10, 7, INK_LIGHT)


def _draw_eye(c):
    # 通用睁眼形状
    outline = [
        (4, 4), (5, 4), (6, 4), (7, 4), (8, 4), (9, 4),
        (3, 5), (10, 5),
        (2, 6), (11, 6),
        (2, 7), (11, 7),
        (3, 8), (10, 8),
        (4, 9), (5, 9), (6, 9), (7, 9), (8, 9), (9, 9),
    ]
    for x, y in outline:
        px(c, x, y, INK)
    # 瞳孔
    pupil = [
        (5, 5), (6, 5), (7, 5), (8, 5),
        (4, 6), (5, 6), (6, 6), (7, 6), (8, 6), (9, 6),
        (4, 7), (5, 7), (6, 7), (7, 7), (8, 7), (9, 7),
        (5, 8), (6, 8), (7, 8), (8, 8),
    ]
    for x, y in pupil:
        px(c, x, y, INK)
    # 高光
    px(c, 5, 5, HIGHLIGHT)
    px(c, 6, 5, HIGHLIGHT)


def icon_hide_off(c):
    # 隐藏未解锁=OFF：睁眼（一切可见）
    _draw_eye(c)


def icon_hide_on(c):
    # 隐藏未解锁=ON：眼睛 + 划线
    _draw_eye(c)
    for i in range(1, 12):
        px(c, i, 12 - i, INK)
        px(c, i + 1, 12 - i, HIGHLIGHT)


# ── 排版顺序 ──
ICONS = [
    # 行 0
    icon_search_open, icon_search_back, icon_sort_asc, icon_sort_desc,
    # 行 1
    icon_cat_default, icon_cat_name, icon_cat_unlock, icon_cat_count,
    # 行 2
    icon_log_time, icon_log_struct, icon_log_dim, icon_log_source,
    # 行 3
    icon_hide_off, icon_hide_on, None, None,
]


def main():
    sheet = Image.new("RGBA", (SHEET_W, SHEET_H), TRANS)
    for idx, drawer in enumerate(ICONS):
        if drawer is None:
            continue
        col = idx % COLS
        row = idx // COLS
        cell = new_cell()
        drawer(cell)
        sheet.paste(cell, (col * CELL, row * CELL), cell)

    out_dir = os.path.normpath(os.path.join(
        os.path.dirname(__file__),
        "..", "common", "src", "main", "resources",
        "assets", "unsuspiciousblock", "textures", "gui"
    ))
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "toolbar_icons.png")
    sheet.save(out_path)
    print(f"saved: {out_path} ({sheet.size[0]}x{sheet.size[1]})")


if __name__ == "__main__":
    main()
