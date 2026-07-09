"""Generate archaeology journal book placeholder texture (384x256 RGBA PNG)."""
from PIL import Image, ImageDraw

W, H = 384, 256

# --- Colors ---
COVER_BROWN = (100, 65, 40, 255)
GUTTER_DARK = (80, 50, 30, 255)
PARCHMENT = (245, 230, 200, 255)
SPINE_CENTER = (60, 38, 22, 255)
CORNER_GOLD = (185, 150, 90, 255)
RIBBON_RED = (170, 45, 45, 255)
RIBBON_DARK = (140, 30, 30, 255)

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# 1. Leather cover (full)
draw.rectangle([0, 0, W - 1, H - 1], fill=COVER_BROWN)

# 2. Page gutters (inset 6px from cover edge)
draw.rectangle([6, 6, W - 7, H - 7], fill=GUTTER_DARK)

# 3. Left page
LEFT_PAGE = (14, 14, 188, 242)
draw.rectangle(LEFT_PAGE, fill=PARCHMENT)

# 4. Right page
RIGHT_PAGE = (196, 14, 370, 242)
draw.rectangle(RIGHT_PAGE, fill=PARCHMENT)

# 5. Spine shadow gradient (vertical strips at center)
for i, alpha in enumerate((220, 180, 140, 100, 80, 100, 140, 180)):
    x = 188 + i
    c = (*SPINE_CENTER[:3], alpha)
    draw.rectangle([x, 14, x, 242], fill=c)

# 6. Corner ornaments (small gold L-shapes at 4 corners of each page)
for x0, y0, x1, y1 in [
    (16, 16, 188, 242),  # left page
    (198, 16, 370, 242),  # right page
]:
    # top-left corner
    draw.rectangle([x0, y0, x0 + 6, y0 + 2], fill=CORNER_GOLD)
    draw.rectangle([x0, y0, x0 + 2, y0 + 6], fill=CORNER_GOLD)
    # top-right corner
    draw.rectangle([x1 - 8, y0, x1 - 2, y0 + 2], fill=CORNER_GOLD)
    draw.rectangle([x1 - 4, y0, x1 - 2, y0 + 6], fill=CORNER_GOLD)
    # bottom-left corner
    draw.rectangle([x0, y1 - 2, x0 + 6, y1], fill=CORNER_GOLD)
    draw.rectangle([x0, y1 - 8, x0 + 2, y1], fill=CORNER_GOLD)
    # bottom-right corner
    draw.rectangle([x1 - 8, y1 - 2, x1 - 2, y1], fill=CORNER_GOLD)
    draw.rectangle([x1 - 4, y1 - 8, x1 - 2, y1], fill=CORNER_GOLD)

# 7. Bookmark ribbon at bottom center
ribbon_cx = W // 2
draw.polygon([
    (ribbon_cx - 8, H - 8),
    (ribbon_cx + 8, H - 8),
    (ribbon_cx + 2, H - 44),
    (ribbon_cx - 2, H - 44),
], fill=RIBBON_RED)
draw.polygon([
    (ribbon_cx - 4, H - 10),
    (ribbon_cx + 4, H - 10),
    (ribbon_cx + 2, H - 42),
    (ribbon_cx - 2, H - 42),
], fill=RIBBON_DARK)

# 8. Page edge lines (inner border on each page)
for x0, y0, x1, y1 in [(15, 15, 187, 241), (197, 15, 369, 241)]:
    draw.rectangle([x0, y0, x1, y0 + 1], fill=(140, 110, 75, 120))
    draw.rectangle([x0, y1 - 1, x1, y1], fill=(140, 110, 75, 120))
    draw.rectangle([x0, y0, x0 + 1, y1], fill=(140, 110, 75, 120))
    draw.rectangle([x1 - 1, y0, x1, y1], fill=(140, 110, 75, 120))

# 9. Save
out = "D:/system_default/desktop/mc_mod_project/unsuspiciousBlock-1.21.1-multi/common/src/main/resources/assets/unsuspiciousblock/textures/gui/archaeology_journal_book.png"
img.save(out)
print(f"Saved: {out} ({W}x{H})")
