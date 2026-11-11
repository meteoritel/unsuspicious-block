"""批量生成、预览或逐像素校验全模组程序化纹理资产。"""
from __future__ import annotations

import sys

from drawlib import run_batch_cli
from generate_book_texture import SPEC as BOOK_SPEC
from generate_bookmark_texture import SPEC as BOOKMARK_SPEC
from generate_lantern_pet_texture import SPEC as LANTERN_PET_SPEC
from generate_log_entry_texture import SPEC as LOG_ENTRY_SPEC
from generate_ui_atlas import SPEC as UI_ATLAS_SPEC

SPECS = (
    UI_ATLAS_SPEC,
    BOOK_SPEC,
    BOOKMARK_SPEC,
    LOG_ENTRY_SPEC,
    LANTERN_PET_SPEC,
)

if __name__ == '__main__':
    sys.exit(run_batch_cli(SPECS, __doc__ or '批量生成或校验全部程序化纹理'))
