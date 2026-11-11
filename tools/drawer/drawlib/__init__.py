"""程序化像素艺术与 UI 纹理生成框架。"""
from pathlib import Path
import sys

_TOOLS_ROOT = Path(__file__).resolve().parents[2]
if str(_TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(_TOOLS_ROOT))

from .atlas import AtlasBuilder, Region, RenderResult
from .canvas import PixelCanvas
from .color import ColorLike, Palette, Rgba, TRANSPARENT, lerp_color, parse_color, with_alpha
from .pixel_art import paint_ascii
from .preview import render_preview
from .spec import TextureSpec, check_texture_parity, run_batch_cli, run_texture_cli, write_texture

__all__ = [
    'AtlasBuilder',
    'ColorLike',
    'Palette',
    'PixelCanvas',
    'Region',
    'RenderResult',
    'Rgba',
    'TRANSPARENT',
    'TextureSpec',
    'check_texture_parity',
    'lerp_color',
    'paint_ascii',
    'parse_color',
    'render_preview',
    'run_batch_cli',
    'run_texture_cli',
    'with_alpha',
    'write_texture',
]
