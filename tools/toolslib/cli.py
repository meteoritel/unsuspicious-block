"""仓库工具共用的控制台与命令行基础辅助。"""
import sys


def configure_console() -> None:
    """将标准输出与错误流配置为 UTF-8，避免 Windows 控制台中文乱码。"""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace')
