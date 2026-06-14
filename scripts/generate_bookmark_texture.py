"""生成书签标签材质 (bookmark_tab.png)
"""

from PIL import Image, ImageDraw

# 宽度拓宽到 40 像素
W = 40
STATE_H = 22
STATES = 3
HEIGHT = STATE_H * STATES

# 配色方案保持原有的经典皮质风格
STATES_CONFIG = [
    {
        'name': 'normal',
        'body': (110, 70, 45, 255),
        'border': (85, 52, 32, 255),
        'highlight': (140, 95, 60, 255),
        'shadow': (75, 45, 28, 255),
    },
    {
        'name': 'hover',
        'body': (145, 100, 65, 255),
        'border': (115, 75, 45, 255),
        'highlight': (180, 135, 90, 255),
        'shadow': (105, 70, 40, 255),
    },
    {
        'name': 'selected',
        'body': (175, 130, 90, 255),
        'border': (140, 100, 65, 255),
        'highlight': (210, 170, 120, 255),
        'shadow': (130, 95, 60, 255),
    },
]

# 手工设计的平滑尖角曲线 profile (整体向右平移 8 像素，使宽度达到 40 像素)
# 顶端和底端宽 32px，中部尖角最宽处达 38px (留出1px给右侧x=39的阴影，完美契合 W=40)
RIGHT_X_PROFILE = [
    32,  # y=0  (顶边)
    33,  # y=1
    33,  # y=2
    34,  # y=3
    34,  # y=4
    35,  # y=5
    35,  # y=6
    36,  # y=7
    36,  # y=8
    37,  # y=9
    38,  # y=10 (尖角顶点，2px宽平头)
    38,  # y=11 (尖角顶点)
    37,  # y=12
    36,  # y=13
    36,  # y=14
    35,  # y=15
    35,  # y=16
    34,  # y=17
    34,  # y=18
    33,  # y=19
    33,  # y=20
    32,  # y=21 (底边)
]

def draw_bookmark_perfect_pixel(draw, y_offset, cfg):
    """使用逐行像素控制绘制一个完美顺滑、立体感强的扁平书签"""

    # 1. 绘制 1px 右下落物阴影 (Drop Shadow)
    # 阴影整体向右移动 1px，向下移动 1px
    for local_y in range(STATE_H):
        rx = RIGHT_X_PROFILE[local_y]
        y = y_offset + local_y + 1
        # 阴影横线，不画到最左边，防止溢出书本边缘
        draw.line([(1, y), (rx + 1, y)], fill=cfg['shadow'])

    # 2. 逐行绘制主体与边框
    for local_y in range(STATE_H):
        rx = RIGHT_X_PROFILE[local_y]
        y = y_offset + local_y

        # 2.1 填充主体底色
        draw.line([(0, y), (rx, y)], fill=cfg['body'])

        # 2.2 绘制左侧边界 (非选中态需要黑边挡住，选中态直接敞开以无缝融入书本)
        if cfg['name'] != 'selected':
            draw.point((0, y), fill=cfg['border'])

        # 2.3 绘制最右侧轮廓黑边
        draw.point((rx, y), fill=cfg['border'])

        # 2.4 绘制顶部与底部 3D 棱边高光和阴影
        if local_y == 0:
            # 顶边：一整条高光
            start_x = 1 if cfg['name'] != 'selected' else 0
            draw.line([(start_x, y), (rx - 1, y)], fill=cfg['highlight'])
        elif local_y == STATE_H - 1:
            # 底边：一整条深色边框
            start_x = 1 if cfg['name'] != 'selected' else 0
            draw.line([(start_x, y), (rx - 1, y)], fill=cfg['shadow'])

        # 2.5 绘制斜面 3D 内侧边缘（极大地增强拟真感）
        if 0 < local_y <= 9:
            # 上斜面：迎光，填充高光
            draw.point((rx - 1, y), fill=cfg['highlight'])
        elif 12 <= local_y < STATE_H - 1:
            # 下斜面：背光，填充阴影
            draw.point((rx - 1, y), fill=cfg['shadow'])
        elif local_y in (10, 11):
            # 顶点内部：高光过渡
            draw.point((rx - 1, y), fill=cfg['highlight'])

    # 3. 绘制垂直装饰条纹（皮质缎带经典凹槽）
    # 在靠近左侧 4px 处绘制一条 2px 宽的竖向高光凹槽
    accent_x = 4
    for local_y in range(2, STATE_H - 2):
        y = y_offset + local_y
        draw.point((accent_x, y), fill=cfg['highlight'])
        draw.point((accent_x + 1, y), fill=cfg['highlight'])


# 创建画布
img = Image.new('RGBA', (W, HEIGHT), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# 渲染三个状态
for i, cfg in enumerate(STATES_CONFIG):
    y_offset = i * STATE_H
    draw_bookmark_perfect_pixel(draw, y_offset, cfg)

# 保存文件
output_path = 'common/src/main/resources/assets/unsuspiciousblock/textures/gui/bookmark_tab.png'
img.save(output_path)
print(f'Generated {output_path}: {W}x{HEIGHT} ({STATES} states x {STATE_H}px)')