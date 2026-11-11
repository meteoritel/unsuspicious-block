"""RGBA 色彩解析、线性插值与不可变像素色板。"""
from __future__ import annotations

from collections.abc import Mapping

Rgba = tuple[int, int, int, int]
ColorLike = str | tuple[int, int, int] | Rgba
TRANSPARENT: Rgba = (0, 0, 0, 0)


def _validate_channel(value: int, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 255:
        raise ValueError(f'{name} 通道须为 0..255 的整数，实际为 {value!r}')
    return value


def parse_color(value: ColorLike) -> Rgba:
    """将十六进制字符串 (#RGB / #RRGGBB / #RRGGBBAA) 或 RGB/RGBA 元组规范化为 RGBA 四元组。"""
    if isinstance(value, str):
        raw = value.strip().lstrip('#')
        if len(raw) == 3:
            r, g, b = (int(ch * 2, 16) for ch in raw)
            return (r, g, b, 255)
        if len(raw) == 6:
            return (int(raw[0:2], 16), int(raw[2:4], 16), int(raw[4:6], 16), 255)
        if len(raw) == 8:
            return (int(raw[0:2], 16), int(raw[2:4], 16), int(raw[4:6], 16), int(raw[6:8], 16))
        raise ValueError(f'无法识别的十六进制颜色：{value!r}')
    if isinstance(value, tuple):
        if len(value) == 3:
            r, g, b = value
            return (_validate_channel(r, 'R'), _validate_channel(g, 'G'), _validate_channel(b, 'B'), 255)
        if len(value) == 4:
            r, g, b, a = value
            return (
                _validate_channel(r, 'R'),
                _validate_channel(g, 'G'),
                _validate_channel(b, 'B'),
                _validate_channel(a, 'A'),
            )
    raise ValueError(f'不支持的颜色规格：{value!r}')


def with_alpha(color: ColorLike, alpha: int) -> Rgba:
    """返回保留 RGB 分量并替换 Alpha 通道的新颜色。"""
    r, g, b, _ = parse_color(color)
    return (r, g, b, _validate_channel(alpha, 'A'))


def lerp_color(start: ColorLike, end: ColorLike, t: float) -> Rgba:
    """在两个颜色间按比例 t (钳制于 [0, 1]) 执行逐通道线性插值。"""
    c0 = parse_color(start)
    c1 = parse_color(end)
    clamped = max(0.0, min(1.0, float(t)))
    return tuple(int(c0[i] + (c1[i] - c0[i]) * clamped) for i in range(4))  # type: ignore[return-value]


class Palette:
    """单字符到 RGBA 颜色的只读色板映射，支持安全查找与继承扩展。"""

    def __init__(self, entries: Mapping[str, ColorLike] | None = None):
        parsed: dict[str, Rgba] = {'.': TRANSPARENT}
        if entries:
            for token, color in entries.items():
                if not isinstance(token, str) or len(token) != 1:
                    raise ValueError(f'色板键须为单字符，实际为 {token!r}')
                parsed[token] = parse_color(color)
        self._entries = parsed

    def __contains__(self, token: str) -> bool:
        return token in self._entries

    def __getitem__(self, token: str) -> Rgba:
        if token not in self._entries:
            raise KeyError(f'色板未定义符号 {token!r}')
        return self._entries[token]

    def extend(self, overrides: Mapping[str, ColorLike]) -> Palette:
        """在当前色板基础上追加或覆盖映射，返回新 Palette。"""
        merged: dict[str, ColorLike] = dict(self._entries)
        merged.update(overrides)
        return Palette(merged)
