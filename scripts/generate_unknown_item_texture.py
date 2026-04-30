"""Generate unknown item silhouette texture for archaeology journal.

Produces a 16x16 RGBA PNG of a black 3D cube silhouette.
Output: textures/gui/unknown_item.png
"""
from PIL import Image, ImageDraw

S = 16

# Colors
BLACK = (0, 0, 0, 255)
EDGE = (40, 40, 40, 255)
BG = (0, 0, 0, 0)

img = Image.new("RGBA", (S, S), BG)
draw = ImageDraw.Draw(img)

# Cube vertices (isometric)
#    Top
#   /  /
#  /__/
#  |  |
#  |__|

# Top face diamond: center-top, right, center, left
top = [(8, 1), (14, 4), (8, 7), (2, 4)]
# Left face
left = [(2, 4), (8, 7), (8, 13), (2, 10)]
# Right face
right = [(8, 7), (14, 4), (14, 10), (8, 13)]

# Draw filled faces
draw.polygon(top, fill=BLACK)
draw.polygon(left, fill=BLACK)
draw.polygon(right, fill=BLACK)

# Draw edges so the 3D structure is readable
draw.polygon(top, outline=EDGE)
draw.polygon(left, outline=EDGE)
draw.polygon(right, outline=EDGE)

# Internal edge (dividing left and right faces)
draw.line([(8, 7), (8, 13)], fill=EDGE)
# Top-right edge
draw.line([(14, 4), (14, 10)], fill=EDGE)
# Top-left edge
draw.line([(2, 4), (2, 10)], fill=EDGE)

out = "D:/system_default/desktop/mc_mod_project/unsuspiciousBlock-1.21.1-multi/common/src/main/resources/assets/unsuspiciousblock/textures/gui/unknown_item.png"
img.save(out)
print(f"Saved: {out} ({S}x{S})")
