"""生成考古手册工具栏图标材质图集。"""

from pathlib import Path

from PIL import Image, ImageDraw


CELL_SIZE = 16
ICON_COLOR = (90, 61, 35, 255)
ICON_NAMES = (
    "SEARCH",
    "CLOSE",
    "SORT_DEFAULT",
    "SORT_NAME",
    "SORT_UNLOCK",
    "SORT_ITEM_COUNT",
    "SORT_FAVORITE",
    "ARROW_UP",
    "ARROW_DOWN",
    "SHOW_LOCKED",
    "HIDE_LOCKED",
)


def pixel(draw, x, y):
    draw.point((x, y), fill=ICON_COLOR)


def horizontal(draw, x, y, length):
    draw.rectangle((x, y, x + length - 1, y), fill=ICON_COLOR)


def vertical(draw, x, y, length):
    draw.rectangle((x, y, x, y + length - 1), fill=ICON_COLOR)


def draw_icon(draw, icon, x, y):
    if icon == "SEARCH":
        horizontal(draw, x + 1, y, 4)
        vertical(draw, x, y + 1, 4)
        vertical(draw, x + 5, y + 1, 4)
        horizontal(draw, x + 1, y + 5, 4)
        for i in range(4):
            pixel(draw, x + 5 + i, y + 5 + i)
    elif icon == "CLOSE":
        for i in range(1, 8):
            pixel(draw, x + i, y + i)
            pixel(draw, x + 8 - i, y + i)
    elif icon == "SORT_DEFAULT":
        for row in (1, 4, 7):
            pixel(draw, x, y + row)
            horizontal(draw, x + 2, y + row, 7)
    elif icon == "SORT_NAME":
        horizontal(draw, x + 2, y, 5)
        vertical(draw, x + 1, y + 1, 8)
        vertical(draw, x + 7, y + 1, 8)
        horizontal(draw, x + 2, y + 4, 5)
    elif icon == "SORT_UNLOCK":
        horizontal(draw, x + 2, y, 5)
        vertical(draw, x + 1, y + 1, 4)
        vertical(draw, x + 7, y + 1, 4)
        horizontal(draw, x + 1, y + 4, 7)
        horizontal(draw, x, y + 5, 9)
        vertical(draw, x, y + 5, 4)
        vertical(draw, x + 8, y + 5, 4)
        horizontal(draw, x, y + 8, 9)
        vertical(draw, x + 4, y + 6, 2)
    elif icon == "SORT_ITEM_COUNT":
        vertical(draw, x + 2, y, 9)
        vertical(draw, x + 6, y, 9)
        horizontal(draw, x, y + 3, 9)
        horizontal(draw, x, y + 6, 9)
    elif icon == "SORT_FAVORITE":
        pixel(draw, x + 4, y)
        horizontal(draw, x + 3, y + 1, 3)
        horizontal(draw, x, y + 3, 9)
        horizontal(draw, x + 1, y + 4, 7)
        horizontal(draw, x + 2, y + 5, 5)
        pixel(draw, x + 2, y + 6)
        pixel(draw, x + 6, y + 6)
        pixel(draw, x + 1, y + 7)
        pixel(draw, x + 7, y + 7)
    elif icon == "ARROW_UP":
        horizontal(draw, x + 3, y + 1, 3)
        horizontal(draw, x + 2, y + 2, 5)
        horizontal(draw, x + 1, y + 3, 7)
        vertical(draw, x + 4, y + 1, 8)
    elif icon == "ARROW_DOWN":
        vertical(draw, x + 4, y, 8)
        horizontal(draw, x + 1, y + 5, 7)
        horizontal(draw, x + 2, y + 6, 5)
        horizontal(draw, x + 3, y + 7, 3)
    elif icon in ("SHOW_LOCKED", "HIDE_LOCKED"):
        horizontal(draw, x + 2, y + 1, 5)
        pixel(draw, x + 1, y + 2)
        pixel(draw, x + 7, y + 2)
        pixel(draw, x, y + 3)
        pixel(draw, x + 8, y + 3)
        horizontal(draw, x + 2, y + 5, 5)
        draw.rectangle((x + 3, y + 2, x + 5, y + 4), fill=ICON_COLOR)
        if icon == "HIDE_LOCKED":
            for i in range(9):
                pixel(draw, x + i, y + 8 - i)


def main():
    output = Path(__file__).resolve().parents[1] / (
        "common/src/main/resources/assets/unsuspiciousblock/textures/gui/journal_icon_atlas.png"
    )
    image = Image.new("RGBA", (CELL_SIZE * len(ICON_NAMES), CELL_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for index, icon in enumerate(ICON_NAMES):
        draw_icon(draw, icon, index * CELL_SIZE + 3, 3)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)
    print(output)


if __name__ == "__main__":
    main()
