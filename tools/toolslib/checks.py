"""统一 CLI、位置诊断和退出码。"""
import argparse
from dataclasses import dataclass
from pathlib import Path
import sys
from typing import Callable

from .repo import repo_root


@dataclass(frozen=True)
class Finding:
    path: str
    rule: str
    detail: str
    line: int | None = None

    def render(self) -> str:
        location = self.path + (f':{self.line}' if self.line is not None else '')
        return f'{location} [{self.rule}] {self.detail}'


def configure_console() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace')


def execute(operation: Callable[[], list[Finding]]) -> int:
    configure_console()
    try:
        findings = operation()
    except (OSError, ValueError) as error:
        print(f'[输入错误] {error}', file=sys.stderr)
        return 2
    for finding in findings:
        print(finding.render())
    if findings:
        return 1
    print('[通过] 检查完成。')
    return 0


def run(check: Callable[[Path], list[Finding]], description: str) -> int:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--root', help='项目根目录，默认由脚本位置定位')
    args = parser.parse_args()
    return execute(lambda: check(repo_root(args.root)))
