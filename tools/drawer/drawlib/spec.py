"""纹理生成规格声明、统一 CLI 入口、批量生成及逐像素一致性校验 (--check)。"""
from __future__ import annotations

import argparse
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
import sys
from PIL import Image, ImageChops

from toolslib.cli import configure_console
from toolslib.repo import repo_root

from .atlas import RenderResult
from .preview import render_preview


@dataclass(frozen=True)
class TextureSpec:
    """单个程序化纹理生成器的元数据与渲染回调契约。"""

    name: str
    description: str
    relative_output: str
    render: Callable[[], RenderResult]
    default_preview_scale: int = 4
    dark_strip_prefixes: tuple[str, ...] = ()


def resolve_output(spec: TextureSpec, root: Path, explicit: Path | None = None) -> Path:
    return explicit.resolve() if explicit is not None else root / spec.relative_output


def check_texture_parity(spec: TextureSpec, root: Path, output: Path | None = None) -> tuple[bool, str]:
    """在内存渲染后与目标 PNG 按 RGBA 逐像素比对，不修改磁盘文件。"""
    target = resolve_output(spec, root, output)
    if not target.is_file():
        return False, f'{spec.name} 缺失目标贴图：{target}'
    result = spec.render()
    generated = result.image.convert('RGBA')
    with Image.open(target) as existing_raw:
        existing = existing_raw.convert('RGBA')
    if generated.size != existing.size:
        return False, f'{spec.name} 尺寸不一致：生成 {generated.size}，现存 {existing.size} ({target})'
    diff_bbox = ImageChops.difference(generated, existing).getbbox()
    if diff_bbox is not None:
        return False, f'{spec.name} 存在像素差异（bbox={diff_bbox}）：{target}'
    return True, f'{spec.name} ({generated.width}x{generated.height}) 逐像素一致'


def write_texture(
    spec: TextureSpec,
    root: Path,
    output: Path | None = None,
    preview: Path | None = None,
) -> Path:
    """执行绘制并写出 PNG（以及可选的预览图）。"""
    result = spec.render()
    target = resolve_output(spec, root, output)
    target.parent.mkdir(parents=True, exist_ok=True)
    result.image.save(target, optimize=True)
    regions_note = f', {len(result.regions)} regions' if result.regions else ''
    print(f'[已生成] {spec.name}: {target} ({result.image.width}x{result.image.height}{regions_note})')
    if preview is not None:
        preview_img = render_preview(
            result,
            scale=spec.default_preview_scale,
            dark_strip_prefixes=spec.dark_strip_prefixes,
        )
        preview.parent.mkdir(parents=True, exist_ok=True)
        preview_img.save(preview)
        print(f'[预览图] {spec.name}: {preview}')
    return target


def run_texture_cli(spec: TextureSpec, argv: Sequence[str] | None = None) -> int:
    """为单个纹理生成器提供统一的 --root / --output / --preview / --check 命令行入口。"""
    configure_console()
    parser = argparse.ArgumentParser(description=spec.description)
    parser.add_argument('--root', help='项目根目录，默认由工具位置定位')
    parser.add_argument('--output', type=Path, default=None, help=f'输出 PNG 路径（默认：{spec.relative_output}）')
    parser.add_argument('--preview', type=Path, default=None, help='可选：输出最近邻放大预览图路径')
    parser.add_argument('--check', action='store_true', help='仅校验当前贴图与生成器输出是否逐像素一致，不写盘')
    args = parser.parse_args(argv)
    try:
        root = repo_root(args.root)
        if args.check:
            matched, message = check_texture_parity(spec, root, args.output)
            print(f'[通过] {message}' if matched else f'[不一致] {message}', file=sys.stdout if matched else sys.stderr)
            return 0 if matched else 1
        write_texture(spec, root, args.output, args.preview)
        return 0
    except (OSError, ValueError, KeyError, AssertionError) as error:
        print(f'[生成失败] {error}', file=sys.stderr)
        return 2


def run_batch_cli(specs: Sequence[TextureSpec], description: str, argv: Sequence[str] | None = None) -> int:
    """批量生成、校验或预览所有已注册的程序化纹理。"""
    configure_console()
    names = [spec.name for spec in specs]
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--root', help='项目根目录，默认由工具位置定位')
    parser.add_argument('--only', action='append', choices=names, help='仅处理指定贴图，可重复')
    parser.add_argument('--check', action='store_true', help='校验所有目标 PNG 与生成器输出逐像素一致')
    parser.add_argument('--preview-dir', type=Path, default=None, help='可选：批量输出预览图到指定目录')
    args = parser.parse_args(argv)
    try:
        root = repo_root(args.root)
        selected = [spec for spec in specs if not args.only or spec.name in args.only]
        all_ok = True
        for spec in selected:
            if args.check:
                matched, message = check_texture_parity(spec, root)
                if matched:
                    print(f'[通过] {message}')
                else:
                    print(f'[不一致] {message}', file=sys.stderr)
                    all_ok = False
            else:
                preview_path = args.preview_dir / f'{spec.name}_preview.png' if args.preview_dir is not None else None
                write_texture(spec, root, preview=preview_path)
        return 0 if all_ok else 1
    except (OSError, ValueError, KeyError, AssertionError) as error:
        print(f'[生成失败] {error}', file=sys.stderr)
        return 2
