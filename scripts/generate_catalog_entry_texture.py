"""Generate catalog entry button textures for the archaeology journal.

Produces a 160x66 texture atlas with 3 states stacked vertically:
  - [0,22]:   normal
  - [22,44]:  hovered
  - [44,66]:  selected

Output: textures/gui/catalog_entry.png
"""
from PIL import Image, ImageDraw

W, H = 160, 66
ROW_H = 22

# --- Colors ---
PARCHMENT_BG = (235, 222, 190, 220)
PARCHMENT_SELECTED = (225, 205, 160, 240)
BORDER_FAINT = (160, 140, 110, 40)
BORDER_HOVER = (185, 150, 90, 100)
BORDER_SELECTED = (185, 150, 90, 200)
HOVER_WARM = (245, 232, 200, 200)
TOP_LINE = (210, 190, 155, 40)

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)


def draw_state(y0, y1, bg_color, border_color, top_line_color):
    """Draw one button state row from y0 to y1."""
    # Background
    draw.rectangle([2, y0 + 1, W - 3, y1 - 1], fill=bg_color)
    # Bottom border
    draw.rectangle([4, y1 - 2, W - 5, y1 - 1], fill=border_color)
    # Top faint line
    draw.rectangle([4, y0 + 1, W - 5, y0 + 2], fill=top_line_color)
    # Left/right subtle edges
    draw.rectangle([2, y0 + 1, 3, y1 - 1], fill=border_color[:3] + (border_color[3] // 2,))
    draw.rectangle([W - 4, y0 + 1, W - 3, y1 - 1], fill=border_color[:3] + (border_color[3] // 2,))


# State 0: Normal
draw_state(0, ROW_H, PARCHMENT_BG, BORDER_FAINT, TOP_LINE)

# State 1: Hovered
draw_state(ROW_H, ROW_H * 2, HOVER_WARM, BORDER_HOVER, BORDER_HOVER)

# State 2: Selected
draw_state(ROW_H * 2, ROW_H * 3, PARCHMENT_SELECTED, BORDER_SELECTED, BORDER_SELECTED)

out = "D:/system_default/desktop/mc_mod_project/unsuspiciousBlock-1.21.1-multi/common/src/main/resources/assets/unsuspiciousblock/textures/gui/catalog_entry.png"
img.save(out)
print(f"Saved: {out} ({W}x{H})")
