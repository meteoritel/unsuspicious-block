"""生成场景页九宫格边框和四态角标，全部按整数像素绘制。"""

from pathlib import Path
from PIL import Image, ImageDraw

OUTPUT = Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/unsuspiciousblock/textures/gui"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    frame = Image.new("RGBA", (24, 24), "#f7ecd2")
    draw = ImageDraw.Draw(frame)
    draw.rectangle((0, 0, 23, 23), outline="#594230", width=1)
    draw.rectangle((1, 1, 22, 22), outline="#b69a6b", width=2)
    draw.rectangle((3, 3, 20, 20), outline="#e1ca9b", width=1)
    draw.rectangle((5, 5, 18, 18), outline="#d8c49d", width=1)
    for x, y in ((3, 3), (20, 3), (3, 20), (20, 20)):
        draw.rectangle((x - 1, y - 1, x + 1, y + 1), fill="#715336")
        draw.point((x, y), fill="#f7dfa1")
    frame.save(OUTPUT / "scenario_frame.png")

    # 未计算（空心点）、计算中（沙漏）、已缓存（勾）、失败（叉）：不只依赖颜色区分。
    badges = Image.new("RGBA", (48, 12), (0, 0, 0, 0))
    for index, color in enumerate(("#7a6247", "#c8a014", "#3a8c3a", "#c06040")):
        x = index * 12
        draw = ImageDraw.Draw(badges)
        draw.rectangle((x + 1, 1, x + 10, 10), fill="#f2e5c6", outline="#594230")
        if index == 0:
            draw.ellipse((x + 4, 4, x + 7, 7), outline=color)
        elif index == 1:
            draw.line([(x + 3, 3), (x + 8, 3), (x + 4, 8), (x + 8, 8), (x + 3, 3)], fill=color)
        elif index == 2:
            draw.line([(x + 3, 6), (x + 5, 8), (x + 8, 3)], fill=color, width=2)
        else:
            draw.line((x + 3, 3, x + 8, 8), fill=color, width=2)
            draw.line((x + 8, 3, x + 3, 8), fill=color, width=2)
    badges.save(OUTPUT / "scenario_status.png")


if __name__ == "__main__":
    main()
