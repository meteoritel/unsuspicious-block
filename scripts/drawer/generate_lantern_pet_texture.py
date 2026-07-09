"""Generate soul lantern pet entity texture (64x64 RGBA PNG).

UV layout (matches LanternPetModel.createBodyLayer):
  ring        (0, 0)   24x7    顶环
  chain       (28, 0)  4x3     链条
  cap_top     (0, 8)   28x8    顶盖
  post_nw     (0, 17)  4x9     西北角柱
  post_ne     (5, 17)  4x9     东北角柱
  post_sw     (10, 17) 4x9     西南角柱
  post_se     (15, 17) 4x9     东南角柱
  glass_n     (0, 27)  12x8    北面玻璃
  glass_s     (13, 27) 12x8    南面玻璃
  glass_e     (26, 27) 12x8    东面玻璃
  glass_w     (39, 27) 12x8    西面玻璃
  flame       (0, 36)  12x8    灵魂火苗
  cap_bottom  (0, 45)  28x8    底盖
  tassel      (30, 45) 8x5     底坠
"""
from pathlib import Path

from PIL import Image, ImageDraw

W, H = 64, 64

OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "common" / "src" / "main" / "resources"
    / "assets" / "unsuspiciousblock" / "textures" / "entity"
    / "soul_lantern_pet.png"
)

# --- Colors ---
# 金属部件（深棕渐变）
METAL_LIGHT = (106, 84, 60, 255)     # #6A543C
METAL_MID = (74, 58, 42, 255)        # #4A3A2A
METAL_DARK = (42, 31, 26, 255)       # #2A1F1A
METAL_EDGE = (30, 22, 18, 255)       # #1E1612

# 玻璃面（深蓝紫色）
GLASS_LIGHT = (40, 60, 100, 255)     # #283C64
GLASS_BASE = (26, 42, 74, 255)       # #1A2A4A
GLASS_EDGE = (20, 30, 55, 255)       # #141E37

# 灵魂火苗（青蓝色）
FLAME_CORE = (90, 224, 255, 255)     # #5AE0FF
FLAME_MID = (60, 170, 255, 255)      # #3CAAFF
FLAME_EDGE = (42, 138, 255, 255)     # #2A8AFF

# 底坠（暗灰金属）
TASSEL_LIGHT = (78, 70, 65, 255)     # #4E4641
TASSEL_DARK = (50, 45, 42, 255)      # #322D2A

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)


def fill_rect(u, v, rw, rh, color):
    """填充 UV 矩形区域"""
    draw.rectangle([u, v, u + rw - 1, v + rh - 1], fill=color)


def fill_gradient_v(u, v, rw, rh, color_top, color_bottom):
    """垂直渐变填充"""
    for y in range(rh):
        t = y / max(rh - 1, 1)
        r = int(color_top[0] + (color_bottom[0] - color_top[0]) * t)
        g = int(color_top[1] + (color_bottom[1] - color_top[1]) * t)
        b = int(color_top[2] + (color_bottom[2] - color_top[2]) * t)
        a = int(color_top[3] + (color_bottom[3] - color_top[3]) * t)
        draw.rectangle([u, v + y, u + rw - 1, v + y], fill=(r, g, b, a))


def draw_edge(u, v, rw, rh, edge_color):
    """在 UV 区域边缘描边"""
    draw.rectangle([u, v, u + rw - 1, v + rh - 1], outline=edge_color)


# === 顶环 (0, 0) 24x7 ===
fill_gradient_v(0, 0, 24, 7, METAL_LIGHT, METAL_DARK)
draw_edge(0, 0, 24, 7, METAL_EDGE)

# === 链条 (28, 0) 4x3 ===
fill_rect(28, 0, 4, 3, METAL_MID)
draw_edge(28, 0, 4, 3, METAL_EDGE)

# === 顶盖 (0, 8) 28x8 ===
fill_gradient_v(0, 8, 28, 8, METAL_MID, METAL_DARK)
draw_edge(0, 8, 28, 8, METAL_EDGE)

# === 4 根角柱 (1x8x1, UV 4x9) ===
for u in (0, 5, 10, 15):
    fill_gradient_v(u, 17, 4, 9, METAL_MID, METAL_DARK)
    draw_edge(u, 17, 4, 9, METAL_EDGE)

# === 4 面玻璃 (UV 12x8) ===
for u in (0, 13, 26, 39):
    fill_gradient_v(u, 27, 12, 8, GLASS_LIGHT, GLASS_BASE)
    draw_edge(u, 27, 12, 8, GLASS_EDGE)

# === 灵魂火苗 (0, 36) 12x8 ===
# 中心亮、边缘暗的水平径向渐变
for x in range(12):
    dist = abs(x - 5)  # 距中心水平距离
    if dist <= 2:
        color = FLAME_CORE
    elif dist <= 4:
        color = FLAME_MID
    else:
        color = FLAME_EDGE
    draw.rectangle([x, 36, x, 36 + 7], fill=color)

# === 底盖 (0, 45) 28x8 ===
fill_gradient_v(0, 45, 28, 8, METAL_DARK, METAL_MID)
draw_edge(0, 45, 28, 8, METAL_EDGE)

# === 底坠 (30, 45) 8x5 ===
fill_gradient_v(30, 45, 8, 5, TASSEL_LIGHT, TASSEL_DARK)
draw_edge(30, 45, 8, 5, METAL_EDGE)

# 确保输出目录存在
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUTPUT)
print(f"已生成贴图: {OUTPUT} ({W}x{H})")
