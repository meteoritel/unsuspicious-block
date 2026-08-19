"""生成考古笔记书侧的配置与帮助索引标签。"""

from pathlib import Path

from PIL import Image, ImageDraw


WIDTH = 24
HEIGHT = 20
PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = (
    PROJECT_ROOT
    / "common/src/main/resources/assets/unsuspiciousblock/textures/gui/book_side_tabs"
)


def draw_tab(palette: dict[str, str], icon_pixels: set[tuple[int, int]]) -> Image.Image:
    """绘制右侧贴合书封、左侧带阶梯切角的像素标签。"""
    image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    outer = [
        (3, 0), (23, 0), (23, 19), (3, 19),
        (3, 18), (1, 18), (1, 16), (0, 16),
        (0, 3), (1, 3), (1, 1), (3, 1),
    ]
    inner = [
        (4, 2), (22, 2), (22, 17), (4, 17),
        (4, 16), (2, 16), (2, 3), (4, 3),
    ]
    draw.polygon(outer, fill=palette["outline"])
    draw.polygon(inner, fill=palette["fill"])

    # 上沿和左沿高光、下沿阴影构成压印感。
    draw.line([(4, 2), (22, 2)], fill=palette["highlight"])
    draw.line([(2, 3), (2, 15)], fill=palette["highlight"])
    draw.line([(4, 17), (22, 17)], fill=palette["shadow"])
    draw.line([(22, 3), (22, 17)], fill=palette["shadow"])

    icon_x = 7
    icon_y = 5
    for x, y in icon_pixels:
        draw.point((icon_x + x, icon_y + y), fill=palette["icon"])

    return image


def gear_icon() -> set[tuple[int, int]]:
    rows = (
        "...###...",
        ".#.###.#.",
        ".#######.",
        "###...###",
        "###.#.###",
        "###...###",
        ".#######.",
        ".#.###.#.",
        "...###...",
    )
    return pixels_from_rows(rows)


def help_icon() -> set[tuple[int, int]]:
    rows = (
        ".#####.",
        "##...##",
        ".....##",
        "....##.",
        "...##..",
        "..##...",
        "..##...",
        ".......",
        "..##...",
    )
    pixels = pixels_from_rows(rows)
    return {(x + 1, y) for x, y in pixels}


def pixels_from_rows(rows: tuple[str, ...]) -> set[tuple[int, int]]:
    return {
        (x, y)
        for y, row in enumerate(rows)
        for x, value in enumerate(row)
        if value == "#"
    }


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    management = draw_tab(
        {
            "outline": "#563517",
            "fill": "#C9923B",
            "highlight": "#F2CF78",
            "shadow": "#8A5724",
            "icon": "#3B2818",
        },
        gear_icon(),
    )
    help_tab = draw_tab(
        {
            "outline": "#34473D",
            "fill": "#7E977E",
            "highlight": "#BDD0AC",
            "shadow": "#526A57",
            "icon": "#F5E7BE",
        },
        help_icon(),
    )

    management.save(OUTPUT_DIR / "management_tab.png", optimize=True)
    help_tab.save(OUTPUT_DIR / "help_tab.png", optimize=True)


if __name__ == "__main__":
    main()
