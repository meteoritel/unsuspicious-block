"""按固定顺序执行源码检查；发布产物由 Gradle 单独准备和校验。"""
import argparse
from pathlib import Path
import subprocess
import sys

from checkslib.checks import configure_console
from toolslib.repo import repo_root

CHECKS = (
    ('docs', 'check-docs.py'),
    ('i18n', 'check-i18n.py'),
    ('isolation', 'check-isolation.py'),
    ('ui-kit', 'check-ui-kit-boundaries.py'),
    ('wiring', 'check-wiring.py'),
)


def main() -> int:
    configure_console()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', help='项目根目录，默认由脚本位置定位')
    parser.add_argument('--only', action='append', choices=[name for name, _ in CHECKS], help='仅执行指定项，可重复；顺序固定')
    args = parser.parse_args()
    try:
        root = repo_root(args.root)
        scripts = Path(__file__).resolve().parent
        for name, script in CHECKS:
            if args.only and name not in args.only:
                continue
            print(f'[执行] {name}', flush=True)
            completed = subprocess.run([sys.executable, '-B', str(scripts / script), '--root', str(root)], check=False)
            if completed.returncode:
                return completed.returncode
        return 0
    except (OSError, ValueError) as error:
        print(f'[输入错误] {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
