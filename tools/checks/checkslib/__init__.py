"""仓库静态检查工具共用的扫描、规则与诊断核心。"""
from pathlib import Path
import sys

_TOOLS_ROOT = Path(__file__).resolve().parents[2]
if str(_TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(_TOOLS_ROOT))
