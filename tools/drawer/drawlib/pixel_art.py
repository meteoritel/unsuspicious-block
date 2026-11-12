"""ASCII 像素字符画解析、图层叠加与校验，消除各脚本内重复的双层像素循环。"""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import TYPE_CHECKING

from .color import Palette, TRANSPARENT

if TYPE_CHECKING:
    from .canvas import PixelCanvas


@dataclass(frozen=True)
class AsciiLayer:
    """单个 ASCII 像素图层，可独立指定色板、水平镜像与挖空字符。"""

    rows: Sequence[str]
    palette: Palette | None = None
    mirror_x: bool = False
    cutout_token: str | None = None


def flip_y_ascii(rows: Sequence[str]) -> tuple[str, ...]:
    """返回上下垂直翻转后的 ASCII 像素模板，便于纵向对称图标单源复用。"""
    if not rows:
        raise ValueError('垂直翻转的 ASCII 模板不能为空')
    width = len(rows[0])
    if width == 0 or any(len(line) != width for line in rows):
        raise ValueError('垂直翻转的 ASCII 模板各行长度须一致且非空')
    return tuple(reversed(rows))


def _validate_grid(
    x: int,
    y: int,
    rows: Sequence[str],
    expected_size: tuple[int, int] | None,
) -> tuple[int, int]:
    if not rows:
        raise ValueError(f'ASCII 像素模板 ({x}, {y}) 不能为空')
    height = len(rows)
    width = len(rows[0])
    if width == 0:
        raise ValueError(f'ASCII 像素模板 ({x}, {y}) 行宽不能为 0')
    if expected_size is not None and (width, height) != expected_size:
        raise ValueError(f'模板 ({x}, {y}) 尺寸须为 {expected_size}，实际为 {(width, height)}')
    for row_index, line in enumerate(rows):
        if len(line) != width:
            raise ValueError(f'模板 ({x}, {y}) 第 {row_index} 行长度不一致：{len(line)} != {width}')
    return width, height


def paint_ascii(
    canvas: PixelCanvas,
    x: int,
    y: int,
    rows: Sequence[str],
    palette: Palette,
    *,
    expected_size: tuple[int, int] | None = None,
    mirror_x: bool = False,
    skip_transparent: bool = False,
    cutout_token: str | None = None,
) -> tuple[int, int]:
    """将等宽 ASCII 字符行绘制到目标画布的 (x, y) 处，返回实际宽高。"""
    width, height = _validate_grid(x, y, rows, expected_size)

    for row_index, line in enumerate(rows):
        for col_index, token in enumerate(line):
            target_x = x + (width - 1 - col_index if mirror_x else col_index)
            if cutout_token is not None and token == cutout_token:
                canvas.pixel(target_x, y + row_index, TRANSPARENT)
                continue
            if token not in palette:
                raise KeyError(f'模板 ({x}, {y}) 在 ({col_index}, {row_index}) 使用了未定义色标 {token!r}')
            color = palette[token]
            if skip_transparent and color == TRANSPARENT:
                continue
            canvas.pixel(target_x, y + row_index, color)
    return width, height


def paint_ascii_layers(
    canvas: PixelCanvas,
    x: int,
    y: int,
    layers: Sequence[Sequence[str] | AsciiLayer],
    default_palette: Palette,
    *,
    expected_size: tuple[int, int] | None = None,
    mirror_x: bool = False,
) -> tuple[int, int]:
    """按顺序叠加多个同尺寸 ASCII 图层；首层全量写入，上层自动透传透明色标。"""
    if not layers:
        raise ValueError(f'ASCII 像素图层列表 ({x}, {y}) 不能为空')

    resolved = [layer if isinstance(layer, AsciiLayer) else AsciiLayer(layer) for layer in layers]
    base_size = _validate_grid(x, y, resolved[0].rows, expected_size)
    for idx, layer in enumerate(resolved):
        paint_ascii(
            canvas,
            x,
            y,
            layer.rows,
            layer.palette or default_palette,
            expected_size=base_size,
            mirror_x=mirror_x ^ layer.mirror_x,
            skip_transparent=idx > 0,
            cutout_token=layer.cutout_token,
        )
    return base_size
