"""
生成考古日志条目背景纹理（精灵图集）。
3个状态各26px高，每个148px宽。
3px 圆角，1px 边框。
"""

from PIL import Image, ImageDraw

WIDTH = 148
STATE_HEIGHT = 26
STATES = 3
HEIGHT = STATE_HEIGHT * STATES
CORNER_RADIUS = 3
BORDER_WIDTH = 1


def rounded_rect_mask(w, h, r):
    """创建圆角矩形mask"""
    mask = Image.new('L', (w, h), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([(0, 0), (w - 1, h - 1)], radius=r, fill=255)
    return mask


def draw_state(draw, y_offset, fill_color, border_color, width, height, radius):
    """绘制单个状态的圆角矩形"""
    # 绘制边框（外层圆角矩形）
    draw.rounded_rectangle(
        [(0, y_offset), (width - 1, y_offset + height - 1)],
        radius=radius,
        fill=border_color
    )
    # 绘制填充（内层，缩小BORDER_WIDTH）
    bw = BORDER_WIDTH
    draw.rounded_rectangle(
        [(bw, y_offset + bw), (width - 1 - bw, y_offset + height - 1 - bw)],
        radius=max(radius - bw, 0),
        fill=fill_color
    )


# 状态颜色 (RGBA) - 羊皮纸融合方案
STATES_CONFIG = [
    # Normal: 极其轻微的纸张凹陷感（柔和沙褐填充 + 浅灰褐边框）
    # 融入背景，不突兀，仅靠浅色边框和微暗的填充勾勒出边界
    {
        'fill': (195, 175, 145, 45),     # 暖沙褐色，在羊皮纸上呈现出淡淡的凹陷阴影
        'border': (165, 145, 120, 80),   # 柔和的浅木质褐
    },
    # Hovered: 悬停提亮（温和的黏土黄填充 + 暖边框）
    # 给玩家明确的交互反馈，色彩变暖变亮
    {
        'fill': (215, 185, 150, 90),     # 亮沙黄，更显眼
        'border': (140, 110, 80, 140),   # 中等巧克力棕，边界变清晰
    },
    # Selected: 选中状态（内敛的熟褐填充 + 传统铜金边框）
    # 既有尊贵感，又不会因为对比度过高而破坏书页的整体温馨感
    {
        'fill': (165, 125, 90, 80),      # 熟褐/皮革色，文字依然使用原版暗色，对比度适中
        'border': (190, 145, 60, 240),   # 复古铜金色边框，非常高档
    },
]

img = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

for i, config in enumerate(STATES_CONFIG):
    y_offset = i * STATE_HEIGHT
    draw_state(draw, y_offset, config['fill'], config['border'],
               WIDTH, STATE_HEIGHT, CORNER_RADIUS)

output_path = 'common/src/main/resources/assets/unsuspiciousblock/textures/gui/log_entry.png'
img.save(output_path)
print(f'Generated {output_path}: {WIDTH}x{HEIGHT} ({STATES} states x {STATE_HEIGHT}px)')