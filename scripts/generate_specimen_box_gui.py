from pathlib import Path

from PIL import Image, ImageDraw

# 已弃用，未来删除
ROOT = Path(__file__).resolve().parents[1]
GUI_DIR = ROOT / "common" / "src" / "main" / "resources" / "assets" / "unsuspiciousblock" / "textures" / "gui"
OUT_COMPOSITE = GUI_DIR / "specimen_box_screen.png"
OUT_MAIN_PANEL = GUI_DIR / "specimen_box_main_panel.png"
OUT_INVENTORY_PANEL = GUI_DIR / "specimen_box_inventory_panel.png"
OUT_SLOT = GUI_DIR / "specimen_box_slot.png"
OUT_CATALOG = GUI_DIR / "specimen_box_catalog_entry.png"

SCREEN_SIZE = (198, 228)
MAIN_PANEL_SIZE = (198, 124)
INVENTORY_PANEL_SIZE = (176, 96)
INVENTORY_PANEL_OFFSET = (11, 132)
SLOT_FRAME_SIZE = 24
CATALOG_WIDTH = 72
CATALOG_ROW_HEIGHT = 18
CATALOG_STATE_COUNT = 3

LEATHER_DARK = (49, 33, 22, 255)
LEATHER_MID = (84, 60, 39, 255)
LEATHER_LIGHT = (126, 95, 63, 255)
BRASS = (210, 179, 111, 255)
BRASS_SHADOW = (118, 86, 50, 255)
PARCHMENT = (236, 223, 196, 255)
PARCHMENT_BRIGHT = (248, 240, 221, 255)
PARCHMENT_SHADE = (204, 181, 146, 255)
WOOD_DARK = (74, 53, 35, 255)
WOOD_MID = (112, 81, 54, 255)
WOOD_LIGHT = (150, 115, 77, 255)
SHADOW = (32, 22, 15, 255)
GLOW = (243, 224, 185, 255)
INVENTORY_DARK = (44, 37, 35, 255)
INVENTORY_MID = (67, 57, 52, 255)
INVENTORY_LIGHT = (94, 79, 72, 255)
GRID_LINE = (255, 255, 255, 26)
GRID_SHADOW = (20, 16, 14, 56)


def fill_vertical_gradient(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    top_color: tuple[int, int, int, int],
    bottom_color: tuple[int, int, int, int],
) -> None:
    x0, y0, x1, y1 = box
    height = max(1, y1 - y0)
    for offset, y in enumerate(range(y0, y1 + 1)):
        mix = offset / height
        color = tuple(int(top_color[i] * (1 - mix) + bottom_color[i] * mix) for i in range(4))
        draw.line([(x0, y), (x1, y)], fill=color)


def draw_outer_frame(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    top_color: tuple[int, int, int, int],
    bottom_color: tuple[int, int, int, int],
) -> None:
    fill_vertical_gradient(draw, box, top_color, bottom_color)
    x0, y0, x1, y1 = box
    draw.rectangle([x0, y0, x1, y1], outline=BRASS)
    draw.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], outline=BRASS_SHADOW)
    draw.rectangle([x0 + 4, y0 + 4, x1 - 4, y1 - 4], outline=(255, 240, 208, 60))


def draw_parchment_panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int]) -> None:
    fill_vertical_gradient(draw, box, PARCHMENT_BRIGHT, PARCHMENT)
    x0, y0, x1, y1 = box
    draw.rectangle([x0, y0, x1, y1], outline=BRASS_SHADOW)
    draw.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], outline=PARCHMENT_BRIGHT)
    draw.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], outline=PARCHMENT_SHADE)


def draw_inventory_panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int]) -> None:
    fill_vertical_gradient(draw, box, INVENTORY_MID, INVENTORY_DARK)
    x0, y0, x1, y1 = box
    draw.rectangle([x0, y0, x1, y1], outline=BRASS)
    draw.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], outline=BRASS_SHADOW)
    draw.rectangle([x0 + 4, y0 + 4, x1 - 4, y1 - 4], outline=(255, 244, 220, 34))

    fill_vertical_gradient(draw, (x0 + 6, y0 + 16, x1 - 6, y1 - 8), INVENTORY_LIGHT, INVENTORY_DARK)
    draw.rectangle([x0 + 6, y0 + 16, x1 - 6, y1 - 8], outline=(23, 20, 18, 200))


def build_catalog_entry_texture() -> Image.Image:
    width = CATALOG_WIDTH
    height = CATALOG_ROW_HEIGHT * CATALOG_STATE_COUNT
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    states = [
        ((72, 50, 34, 244), (102, 72, 47, 244), (165, 129, 83, 180)),
        ((96, 71, 49, 255), (133, 98, 67, 255), (224, 192, 146, 220)),
        ((118, 89, 59, 255), (160, 122, 81, 255), (246, 223, 183, 255)),
    ]
    for state_index, (top, bottom, outline) in enumerate(states):
        y0 = state_index * CATALOG_ROW_HEIGHT
        y1 = y0 + CATALOG_ROW_HEIGHT - 1
        fill_vertical_gradient(draw, (0, y0, width - 1, y1), top, bottom)
        draw.rectangle([0, y0, width - 1, y1], outline=outline)
        draw.rectangle([1, y0 + 1, width - 2, y1 - 1], outline=(255, 247, 226, 70))
    return img


def build_slot_texture() -> Image.Image:
    img = Image.new("RGBA", (SLOT_FRAME_SIZE, SLOT_FRAME_SIZE * 2), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    states = [
        ((95, 69, 45, 255), (59, 42, 28, 255), (182, 148, 102, 255)),
        ((146, 112, 73, 255), (86, 61, 39, 255), (238, 213, 170, 255)),
    ]
    for state_index, (border, inset, highlight) in enumerate(states):
        y0 = state_index * SLOT_FRAME_SIZE
        draw.rectangle([0, y0, SLOT_FRAME_SIZE - 1, y0 + SLOT_FRAME_SIZE - 1], fill=border)
        draw.rectangle([1, y0 + 1, SLOT_FRAME_SIZE - 2, y0 + SLOT_FRAME_SIZE - 2], fill=SHADOW)
        draw.rectangle([2, y0 + 2, SLOT_FRAME_SIZE - 3, y0 + SLOT_FRAME_SIZE - 3], fill=inset)
        draw.rectangle([2, y0 + 2, SLOT_FRAME_SIZE - 3, y0 + SLOT_FRAME_SIZE - 3], outline=highlight)
        draw.rectangle([5, y0 + 5, SLOT_FRAME_SIZE - 6, y0 + SLOT_FRAME_SIZE - 6], outline=(255, 248, 226, 52))
    return img


def build_main_panel_texture() -> Image.Image:
    width, height = MAIN_PANEL_SIZE
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    draw_outer_frame(draw, (0, 0, width - 1, height - 1), LEATHER_LIGHT, LEATHER_DARK)

    fill_vertical_gradient(draw, (8, 7, width - 9, 20), WOOD_LIGHT, WOOD_MID)
    draw.rectangle([8, 7, width - 9, 20], outline=BRASS_SHADOW)

    fill_vertical_gradient(draw, (96, 26, 99, 117), BRASS, BRASS_SHADOW)
    for y in (42, 68, 94):
        draw.rectangle([95, y, 100, y + 8], fill=GLOW)
        draw.rectangle([96, y + 1, 99, y + 7], fill=BRASS)

    draw_parchment_panel(draw, (10, 26, 86, 120))
    draw_parchment_panel(draw, (104, 26, 188, 120))

    fill_vertical_gradient(draw, (110, 10, 186, 22), WOOD_LIGHT, WOOD_MID)
    draw.rectangle([110, 10, 186, 22], outline=BRASS_SHADOW)

    fill_vertical_gradient(draw, (116, 92, 179, 113), WOOD_LIGHT, WOOD_MID)
    draw.rectangle([116, 92, 179, 113], outline=BRASS_SHADOW)

    return img


def build_inventory_panel_texture() -> Image.Image:
    width, height = INVENTORY_PANEL_SIZE
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    draw_inventory_panel(draw, (0, 0, width - 1, height - 1))

    for x in range(8, 171, 18):
        draw.line([(x, 18), (x, 93)], fill=GRID_LINE)
    for y in (18, 36, 54, 76, 94):
        draw.line([(8, y), (169, y)], fill=GRID_SHADOW)

    return img


def build_composite_background(main_panel: Image.Image, inventory_panel: Image.Image) -> Image.Image:
    img = Image.new("RGBA", SCREEN_SIZE, (0, 0, 0, 0))
    img.alpha_composite(main_panel, (0, 0))
    img.alpha_composite(inventory_panel, INVENTORY_PANEL_OFFSET)
    return img


GUI_DIR.mkdir(parents=True, exist_ok=True)
main_panel = build_main_panel_texture()
inventory_panel = build_inventory_panel_texture()
composite = build_composite_background(main_panel, inventory_panel)

composite.save(OUT_COMPOSITE)
main_panel.save(OUT_MAIN_PANEL)
inventory_panel.save(OUT_INVENTORY_PANEL)
build_slot_texture().save(OUT_SLOT)
build_catalog_entry_texture().save(OUT_CATALOG)
print(f"Saved: {OUT_COMPOSITE}")
print(f"Saved: {OUT_MAIN_PANEL}")
print(f"Saved: {OUT_INVENTORY_PANEL}")
print(f"Saved: {OUT_SLOT}")
print(f"Saved: {OUT_CATALOG}")
