"""RGBA 像素画布及常用 UI/模型贴图几何原语，全部尺寸参数均采用左闭宽高语义 (x, y, w, h)。"""
from __future__ import annotations

from collections.abc import Sequence
from PIL import Image, ImageDraw

from .color import ColorLike, Palette, TRANSPARENT, lerp_color, parse_color
from .pixel_art import paint_ascii


class PixelCanvas:
    """对 PIL RGBA Image/ImageDraw 的安全封装，避免 [x0, y0, x1, y1] 闭区间差一错误。"""

    def __init__(self, width: int, height: int, bg: ColorLike = TRANSPARENT):
        if width <= 0 or height <= 0:
            raise ValueError(f'画布宽高须为正整数，实际为 ({width}, {height})')
        self._image = Image.new('RGBA', (width, height), parse_color(bg))
        self._draw = ImageDraw.Draw(self._image)

    @property
    def width(self) -> int:
        return self._image.width

    @property
    def height(self) -> int:
        return self._image.height

    @property
    def image(self) -> Image.Image:
        return self._image

    def pixel(self, x: int, y: int, color: ColorLike) -> PixelCanvas:
        if not (0 <= x < self.width and 0 <= y < self.height):
            raise ValueError(f'像素坐标越界 ({x}, {y})，画布尺寸 {self.width}x{self.height}')
        self._image.putpixel((x, y), parse_color(color))
        return self

    def pixels(self, points: Sequence[tuple[int, int]], color: ColorLike) -> PixelCanvas:
        rgba = parse_color(color)
        for px, py in points:
            self.pixel(px, py, rgba)
        return self

    def line(self, x0: int, y0: int, x1: int, y1: int, color: ColorLike) -> PixelCanvas:
        self._draw.line([(x0, y0), (x1, y1)], fill=parse_color(color))
        return self

    def hline(self, x: int, y: int, length: int, color: ColorLike) -> PixelCanvas:
        if length > 0:
            self._draw.line([(x, y), (x + length - 1, y)], fill=parse_color(color))
        return self

    def vline(self, x: int, y: int, length: int, color: ColorLike) -> PixelCanvas:
        if length > 0:
            self._draw.line([(x, y), (x, y + length - 1)], fill=parse_color(color))
        return self

    def fill_rect(self, x: int, y: int, w: int, h: int, color: ColorLike) -> PixelCanvas:
        if w > 0 and h > 0:
            self._draw.rectangle([x, y, x + w - 1, y + h - 1], fill=parse_color(color))
        return self

    def stroke_rect(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        border: ColorLike,
        *,
        fill: ColorLike | None = None,
        border_width: int = 1,
    ) -> PixelCanvas:
        if w > 0 and h > 0:
            self._draw.rectangle(
                [x, y, x + w - 1, y + h - 1],
                fill=parse_color(fill) if fill is not None else None,
                outline=parse_color(border),
                width=border_width,
            )
        return self

    def fill_gradient_v(self, x: int, y: int, w: int, h: int, top: ColorLike, bottom: ColorLike) -> PixelCanvas:
        c_top = parse_color(top)
        c_bot = parse_color(bottom)
        denom = max(h - 1, 1)
        for row in range(h):
            self.hline(x, y + row, w, lerp_color(c_top, c_bot, row / denom))
        return self

    def rounded_rect(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        radius: int,
        *,
        fill: ColorLike | None = None,
        border: ColorLike | None = None,
        border_width: int = 1,
    ) -> PixelCanvas:
        if border is not None:
            self._draw.rounded_rectangle(
                [(x, y), (x + w - 1, y + h - 1)],
                radius=radius,
                fill=parse_color(border),
            )
            if fill is not None:
                bw = border_width
                self._draw.rounded_rectangle(
                    [(x + bw, y + bw), (x + w - 1 - bw, y + h - 1 - bw)],
                    radius=max(radius - bw, 0),
                    fill=parse_color(fill),
                )
        elif fill is not None:
            self._draw.rounded_rectangle(
                [(x, y), (x + w - 1, y + h - 1)],
                radius=radius,
                fill=parse_color(fill),
            )
        return self

    def bevel_rect(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        *,
        border: ColorLike,
        fill: ColorLike,
        top_left: ColorLike,
        bottom: ColorLike | None = None,
        right: ColorLike | None = None,
    ) -> PixelCanvas:
        """绘制带直角外框与内斜面高光/阴影的面板底纹（供 9-slice 面板共用）。"""
        self.stroke_rect(x, y, w, h, border, fill=fill)
        if w > 2 and h > 2:
            self.hline(x + 1, y + 1, w - 2, top_left)
            self.vline(x + 1, y + 1, h - 2, top_left)
            if bottom is not None:
                self.hline(x + 1, y + h - 2, w - 2, bottom)
            if right is not None:
                self.vline(x + w - 2, y + 1, h - 2, right)
        return self

    def chamfer_panel(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        *,
        border: ColorLike,
        fill: ColorLike,
        highlight: ColorLike | None = None,
        shade: ColorLike | None = None,
        highlight_width: int = 1,
        shade_width: int = 0,
    ) -> PixelCanvas:
        """绘制带 1px 切角圆润外框与可调厚度内斜面的像素盒（供按钮九宫格、状态角标等共用）。"""
        c_border = parse_color(border)
        c_fill = parse_color(fill)
        c_hi = parse_color(highlight) if highlight is not None else None
        c_sh = parse_color(shade) if shade is not None else None
        for ly in range(h):
            for lx in range(w):
                if lx in (0, w - 1) and ly in (0, h - 1):
                    continue
                is_edge = lx in (0, w - 1) or ly in (0, h - 1) or (lx in (1, w - 2) and ly in (1, h - 2))
                if is_edge:
                    col = c_border
                elif c_hi is not None and highlight_width > 0 and (ly < 1 + highlight_width or lx < 1 + highlight_width):
                    col = c_hi
                elif c_sh is not None and shade_width > 0 and (ly >= h - 1 - shade_width or lx >= w - 1 - shade_width):
                    col = c_sh
                else:
                    col = c_fill
                self.pixel(x + lx, y + ly, col)
        return self

    def polygon(self, points: Sequence[tuple[int, int]], fill: ColorLike, *, outline: ColorLike | None = None) -> PixelCanvas:
        self._draw.polygon(
            list(points),
            fill=parse_color(fill),
            outline=parse_color(outline) if outline is not None else None,
        )
        return self

    def put_ascii(
        self,
        x: int,
        y: int,
        rows: Sequence[str],
        palette: Palette,
        *,
        expected_size: tuple[int, int] | None = None,
        mirror_x: bool = False,
        skip_transparent: bool = False,
    ) -> PixelCanvas:
        paint_ascii(
            self,
            x,
            y,
            rows,
            palette,
            expected_size=expected_size,
            mirror_x=mirror_x,
            skip_transparent=skip_transparent,
        )
        return self

    def composite(self, other: PixelCanvas, x: int = 0, y: int = 0, *, alpha_blend: bool = True) -> PixelCanvas:
        if alpha_blend:
            self._image.alpha_composite(other.image, (x, y))
        else:
            self._image.paste(other.image, (x, y))
        return self

    def mirror_x(self) -> PixelCanvas:
        out = PixelCanvas(self.width, self.height)
        out._image = self._image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        out._draw = ImageDraw.Draw(out._image)
        return out
