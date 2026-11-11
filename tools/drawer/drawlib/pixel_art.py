"""ASCII 像素字符画解析与校验，消除各脚本内重复的双层像素循环。"""
from __future__ import annotations

from collections.abc import Sequence
from typing import TYPE_CHECKING

from .color import Palette, TRANSPARENT

if TYPE_CHECKING:
    from .canvas import PixelCanvas


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
) -> tuple[int, int]:
    """将等宽 ASCII 字符行绘制到目标画布的 (x, y) 处，返回实际宽高。"""
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
        for col_index, token in enumerate(line):
            if token not in palette:
                raise KeyError(f'模板 ({x}, {y}) 在 ({col_index}, {row_index}) 使用了未定义色标 {token!r}')
            color = palette[token]
            if skip_transparent and color == TRANSPARENT:
                continue
            target_x = x + (width - 1 - col_index if mirror_x else col_index)
            canvas.pixel(target_x, y + row_index, color)
    return width, height
