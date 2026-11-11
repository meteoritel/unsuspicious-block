"""图集区域注册、局部坐标绘制托管与即时非重叠校验 (fail-fast)。"""
from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from PIL import Image

from .canvas import PixelCanvas
from .color import ColorLike, Palette, TRANSPARENT


@dataclass(frozen=True)
class Region:
    """图集或精灵条带中的一个命名矩形区块。"""

    name: str
    x: int
    y: int
    width: int
    height: int

    def overlaps(self, other: Region) -> bool:
        return (
            self.x < other.x + other.width
            and self.x + self.width > other.x
            and self.y < other.y + other.height
            and self.y + self.height > other.y
        )


@dataclass(frozen=True)
class RenderResult:
    """贴图生成器的输出：主图像与可选区域清单。"""

    image: Image.Image
    regions: tuple[Region, ...] = ()


class AtlasBuilder:
    """带自动边界/碰撞检测与局部坐标系托管的图集构建器。"""

    def __init__(self, width: int, height: int, bg: ColorLike = TRANSPARENT):
        self.canvas = PixelCanvas(width, height, bg)
        self._regions: list[Region] = []
        self._names: set[str] = set()

    @property
    def regions(self) -> tuple[Region, ...]:
        return tuple(self._regions)

    def reserve(self, name: str, x: int, y: int, width: int, height: int) -> Region:
        """在构建期声明图集区块，发现命名冲突、越界或与已有区块重叠时立即失败。"""
        if not name:
            raise ValueError('区块名称不能为空')
        if name in self._names:
            raise ValueError(f'重复的图集区块名称：{name}')
        region = Region(name, x, y, width, height)
        if x < 0 or y < 0 or x + width > self.canvas.width or y + height > self.canvas.height:
            raise AssertionError(
                f'{name} 超出画布范围 ({self.canvas.width}x{self.canvas.height})：{(x, y, width, height)}'
            )
        for existing in self._regions:
            if region.overlaps(existing):
                raise AssertionError(f'图集区块重叠：{name} 与 {existing.name}')
        self._names.add(name)
        self._regions.append(region)
        return region

    def paint(
        self,
        name: str,
        x: int,
        y: int,
        width: int,
        height: int,
        painter: Callable[[PixelCanvas], None],
    ) -> Region:
        """分配局部 (0..w-1, 0..h-1) 画布交由 painter 绘制并贴合至主图集。"""
        region = self.reserve(name, x, y, width, height)
        local = PixelCanvas(width, height)
        painter(local)
        self.canvas.composite(local, x, y, alpha_blend=False)
        return region

    def place_ascii(
        self,
        name: str,
        x: int,
        y: int,
        rows: Sequence[str],
        palette: Palette,
        *,
        expected_size: tuple[int, int] | None = None,
        mirror_x: bool = False,
    ) -> Region:
        """按模板尺寸预留图集区块并绘制 ASCII 字符画。"""
        if not rows:
            raise ValueError(f'{name} 的 ASCII 模板不能为空')
        width, height = len(rows[0]), len(rows)
        region = self.reserve(name, x, y, width, height)
        self.canvas.put_ascii(x, y, rows, palette, expected_size=expected_size, mirror_x=mirror_x)
        return region

    def place_grid_ascii(
        self,
        col: int,
        row: int,
        cell_w: int,
        cell_h: int,
        name: str,
        rows: Sequence[str],
        palette: Palette,
        *,
        origin: tuple[int, int] = (0, 0),
    ) -> Region:
        """按固定网格槽位坐标放置等宽高 ASCII 图标。"""
        x = origin[0] + col * cell_w
        y = origin[1] + row * cell_h
        return self.place_ascii(name, x, y, rows, palette, expected_size=(cell_w, cell_h))

    def build(self) -> RenderResult:
        return RenderResult(self.canvas.image, tuple(self._regions))
