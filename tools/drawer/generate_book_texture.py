"""生成考古笔记双页书本背景纹理 (textures/gui/archaeology_journal_book.png, 384x256 RGBA)。"""
from __future__ import annotations

from drawlib import PixelCanvas, RenderResult, TextureSpec, run_texture_cli, with_alpha

W, H = 384, 256

COVER_BROWN = (100, 65, 40, 255)
GUTTER_DARK = (80, 50, 30, 255)
PARCHMENT = (245, 230, 200, 255)
SPINE_CENTER = (60, 38, 22, 255)
CORNER_GOLD = (185, 150, 90, 255)
RIBBON_RED = (170, 45, 45, 255)
RIBBON_DARK = (140, 30, 30, 255)
PAGE_EDGE = (140, 110, 75, 120)
SPINE_ALPHAS = (220, 180, 140, 100, 80, 100, 140, 180)


def render() -> RenderResult:
    """绘制皮革封面、双羊皮纸书页、书脊渐变、金角饰纹与底部红丝带书签。"""
    c = PixelCanvas(W, H)

    # 1. 皮革封面与内嵌书槽底
    c.fill_rect(0, 0, W, H, COVER_BROWN)
    c.fill_rect(6, 6, W - 12, H - 12, GUTTER_DARK)

    # 2. 左右羊皮纸页面
    c.fill_rect(14, 14, 175, 229, PARCHMENT)
    c.fill_rect(196, 14, 175, 229, PARCHMENT)

    # 3. 中缝书脊竖向阴影渐变
    for i, alpha in enumerate(SPINE_ALPHAS):
        c.vline(188 + i, 14, 229, with_alpha(SPINE_CENTER, alpha))

    # 4. 页面四角 L 形金色角饰
    for x0, y0, x1, y1 in ((16, 16, 188, 242), (198, 16, 370, 242)):
        for rx, ry, rw, rh in (
            (x0, y0, 7, 3),
            (x0, y0, 3, 7),
            (x1 - 8, y0, 7, 3),
            (x1 - 4, y0, 3, 7),
            (x0, y1 - 2, 7, 3),
            (x0, y1 - 8, 3, 7),
            (x1 - 8, y1 - 2, 7, 3),
            (x1 - 4, y1 - 8, 3, 7),
        ):
            c.fill_rect(rx, ry, rw, rh, CORNER_GOLD)

    # 5. 底部中央书签红丝带
    cx = W // 2
    c.polygon([(cx - 8, H - 8), (cx + 8, H - 8), (cx + 2, H - 44), (cx - 2, H - 44)], fill=RIBBON_RED)
    c.polygon([(cx - 4, H - 10), (cx + 4, H - 10), (cx + 2, H - 42), (cx - 2, H - 42)], fill=RIBBON_DARK)

    # 6. 页面 2px 浅棕内边框
    for px in (15, 197):
        c.stroke_rect(px, 15, 173, 227, PAGE_EDGE, border_width=2)

    return RenderResult(c.image)


SPEC = TextureSpec(
    name='book',
    description=__doc__ or '生成考古笔记双页书本背景纹理',
    relative_output='common/src/main/resources/assets/unsuspiciousblock/textures/gui/archaeology_journal_book.png',
    render=render,
    default_preview_scale=2,
)

if __name__ == '__main__':
    raise SystemExit(run_texture_cli(SPEC))
