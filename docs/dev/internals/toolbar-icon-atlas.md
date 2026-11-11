# UI 图标与控件皮肤图集

本文档记录 `assets/unsuspiciousblock/textures/gui/ui_atlas.png` 的分区坐标与槽位映射，供 [`UiAtlas`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/UiAtlas.java)、[`JournalControls`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalControls.java)、[`UnsuspiciousUiTheme`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/UnsuspiciousUiTheme.java) 及各界面组件统一取样。

## 1. 图集规格与生成入口

| 项目 | 规格 |
|---|---|
| 图集路径 | `common/src/main/resources/assets/unsuspiciousblock/textures/gui/ui_atlas.png` |
| 图集尺寸 | `256 x 128 px`（RGBA 像素风，最近邻采样） |
| 生成脚本 | [`generate_ui_atlas.py`](../../../scripts/drawer/generate_ui_atlas.py) |
| 取样契约 | `UiAtlas` 显式传入 `textureWidth = 256`、`textureHeight = 128` 构造 `UiIcon.Sprite` 与 `UiNineSlice` |

> `UiIconSheet` 按规则网格宽高乘积推导贴图尺寸；由于 `ui_atlas.png` 混排多种尺寸精灵与九宫格，[`UiAtlas`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/UiAtlas.java) 使用带总尺寸参数的 `UiIcon.Sprite` 与 `UiNineSlice` 取样，禁止手算或硬编码散落 UV。

## 2. 分区规划

| 分区 | 坐标范围 | 包含内容 |
|---|---|---|
| **Zone 1** | `(0, 0)` – `(119, 49)` | `10 x 10 px` 功能与行内图标网格（`12 列 x 5 行`：`u = col * 10`, `v = row * 10`） |
| **Zone 2** | `(128, 0)` – `(211, 33)` | `12 x 12` 场景计算状态角标（4 态）、`16 x 16` 未知物品、`16 x 16` 猫爪 HUD、3 枚 `16 x 16` 外链图标 |
| **Zone 3** | `(0, 52)` – `(97, 91)` | `23 x 13` 羊皮纸翻页箭头（常态 / 悬停 / 禁用）与 `24 x 20` 书本左侧阶梯标签底板（管理 / 帮助 各 2 态） |
| **Zone 4** | `(104, 52)` – `(175, 87)` | `12 x 12` 9-slice 按钮与面板底纹（常规羊皮纸 5 态、铜金强调 4 态、轻量内嵌常态与场景面板底纹） |

## 3. Zone 1 (`10 x 10` 图标网格映射)

| 行 | 列 | `UiAtlas.Icon` 枚举 | 图标用途 |
|---:|---:|---|---|
| 0 | 0 | `SEARCH` | 放大镜，目录搜索 |
| 0 | 1 | `CLOSE` | 关闭 / 清除 / 删除当前日志条目 |
| 0 | 2 | `ARROW_UP` | 正序 / 升序 |
| 0 | 3 | `ARROW_DOWN` | 倒序 / 降序 |
| 0 | 4 | `SHOW_LOCKED` | 睁眼，显示未解锁条目 |
| 0 | 5 | `HIDE_LOCKED` | 划线眼，隐藏未解锁条目 |
| 0 | 6 | `SORT_DEFAULT` | 默认排序 |
| 0 | 7 | `SORT_NAME` | 按名称排序 |
| 0 | 8 | `SORT_ITEM_COUNT` | 按数量排序 |
| 0 | 9 | `SORT_FAVORITE` | 收藏排序 |
| 0 | 10 | `SORT_UNLOCK` | 按解锁状态排序 |
| 0 | 11 | `SORT_UPDATE_TIME` | 按更新时间排序 |
| 1 | 0 | `BACK` | 返回（历史回退、分类返回、Tag 返回、日志详情返回） |
| 1 | 1 | `ENABLED` / `CHECK` | 对钩（两常量共用槽位，日志批量确认等） |
| 1 | 2 | `DISABLED` | 关闭的锁（通用禁用态图标） |
| 1 | 3 | `REDSTONE` | 通用红石图标 |
| 1 | 4 | `GEAR` | 齿轮（日志保留配置、`MANAGEMENT` 侧签叠加图标） |
| 1 | 5 | `HELP` | 羊皮纸深棕问号 |
| 1 | 6 | `PENCIL` | 铅笔（编辑备注 / 按备注分组） |
| 1 | 7 | `CLOCK` | 时钟（按时间分组） |
| 1 | 8 | `BIOME` | 小树（按群系分组） |
| 1 | 9 | `DIMENSION` | 维度方框（按维度分组） |
| 1 | 10 | `TRASH_EMPTY` | 空垃圾桶（清除当前表日志） |
| 1 | 11 | `TRASH_FULL` | 满垃圾桶（清除全部日志） |
| 2 | 0 | `SQUARE` | 方框（开启批量选择） |
| 2 | 1 | `GROUP` | 四方格（退出批量选择） |
| 2 | 2 | `COPY` | 重叠双页（`CopyCoordinateButton` 复制坐标） |
| 2 | 3 | `EXPAND` | 全屏放大（展开条件灯箱） |
| 2 | 4 | `FIT` | 适应窗口（灯箱 `fit`） |
| 2 | 5 | `PLUS` | 加号（灯箱放大） |
| 2 | 6 | `MINUS` | 减号（灯箱缩小） |
| 2 | 7 | `PARAMS` | 滑杆调校（打开场景参数配置） |
| 2 | 8 | `CALCULATE` | 开始 / 计算（触发场景概率模拟） |
| 2 | 9 | `RECOMMEND` | 四芒星辉（子表与路径推荐求解） |
| 2 | 10 | `CHEVRON_DOWN` | 向下折角（场景选择下拉、展开其它条件） |
| 2 | 11 | `CHEVRON_UP` | 向上折角（收起其它条件） |
| 3 | 0 | `TREE_COLLAPSED` | 目录树折叠箭头 |
| 3 | 1 | `TREE_EXPANDED` | 目录树展开箭头 |
| 3 | 2 | `FAVORITE_BADGE` | 目录条目收藏金星 |
| 3 | 3 | `CHILD_BADGE` | 目录条目子表枝节标记 |
| 3 | 4 | `NOTE_BADGE` | 日志列表条目备注标记（常态） |
| 3 | 5 | `NOTE_BADGE_HOVER` | 日志列表条目备注标记（悬停） |
| 3 | 6 | `CHECKBOX_UNCHECKED` | 日志批量选择未勾选框 |
| 3 | 7 | `CHECKBOX_CHECKED` | 日志批量选择已勾选框 |
| 3 | 8 | `BRANCH_ARROW` | 场景条件候选行引导箭头 |
| 3 | 9 | `HELP_CREAM` | 浅米高对比问号（`HELP` 侧签叠加图标） |
| 3 | 10 | `DARK_TREE_COLLAPSED` | 管理界面暗底折叠箭头 |
| 3 | 11 | `DARK_TREE_EXPANDED` | 管理界面暗底展开箭头 |
| 4 | 0 | `DARK_TRACKED` | 管理界面暗底已追踪标记 |
| 4 | 1 | `DARK_UNTRACKED` | 管理界面暗底未追踪标记 |
| 4 | 2 | `POTTERY_ENABLED` | [`PotteryWheelScreen`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/screen/PotteryWheelScreen.java) 暗底启用态对钩 |
| 4 | 3 | `POTTERY_DISABLED` | `PotteryWheelScreen` 暗底禁用态闭合锁 |
| 4 | 4 | `POTTERY_REDSTONE` | `PotteryWheelScreen` 暗底红石启用态图标 |
| 4 | 5–11 | — | 预留槽位 |

## 4. Zone 2 – Zone 4 专项精灵与皮肤映射

- **Zone 2（固定尺寸精灵）**：
  - `statusBadge(status)`：`12 x 12` 位于 `(128..175, 0)`，依次对应 `uncomputed`、`pending/querying/retrieving`、`cached/available`、`failed/query_failed`。
  - `UNKNOWN_ITEM`：`16 x 16` 位于 `(180, 0)`，供未发现物品卡片与场景未解锁分支共用。
  - `CAT_FAVOR_HUD`：`16 x 16` 中性白灰可染色猫爪位于 `(196, 0)`，供 [`CatFavorHud`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/CatFavorHud.java) 按阶段色调制绘制（状态效果图标仍保留独立 `textures/mob_effect/cat_favor.png`）。
  - `EXTERNAL_GITHUB` / `EXTERNAL_CURSEFORGE` / `EXTERNAL_MODRINTH`：`16 x 16` 依次位于 `(128, 18)`、`(144, 18)`、`(160, 18)`。
- **Zone 3（控件组合精灵）**：
  - `pageArrow`：上一页 `(0, 52 / 65 / 78)`、下一页 `(23, 52 / 65 / 78)`，规格 `23 x 13`。
  - `sideTabPlate`：`MANAGEMENT` `(50 / 74, 52)`、`HELP` `(50 / 74, 72)`，规格 `24 x 20`（先画剪影底板再叠加 `GEAR` / `HELP_CREAM`）。
- **Zone 4（9-Slice 皮肤与面板）**：
  - 羊皮纸常规按钮（`PARCHMENT_BUTTON_SKIN`，`corner = 2`）：`(104..152, 52)` 五态（Normal / Hover / Pressed / Selected / Disabled）。
  - 铜金强调按钮（`ACCENT_BUTTON_SKIN`，`corner = 2`）：`(104..140, 64)` 四态（Normal / Hover / Pressed / Disabled）。
  - 轻量内嵌按钮（`QUIET_BUTTON_SKIN`，`corner = 2`）：常态 `(104, 76)` 衔接羊皮纸悬停/按压态，供场景下拉标题与 `CopyCoordinateButton` 使用。
  - 场景条件底板（`SCENARIO_PANEL`，`corner = 3`）：`(164, 52, 12, 12)`。

## 5. 使用与修改约束

- **禁止修改已有槽位坐标**：新增图标仅追加至 `Row 4 (col 5..11)` 或图集空白区，并同步更新 `generate_ui_atlas.py` 与 `UiAtlas.java`。
- **保持独立的大尺寸背景纹理**：书本背景（`archaeology_journal_book.png` `384x256`）、陶轮容器底图（`pottery_wheel_gui.png` `256x256`）、定宽条目条带（`catalog_entry.png`、`log_entry.png`、`bookmark_tab.png`）与标本箱预览（`specimen_box_preview.png`）保持各自独立 PNG。
- **修改纹理必须经过生成器**：修改图标或皮肤像素时更新 `scripts/drawer/generate_ui_atlas.py` 并重新运行生成，保证代码与 PNG 同源。

## 6. 相关文档

- [客户端与 GUI](../subsystems/client-ui.md)
- [笔记 GUI 内部机制](journal-ui-internals.md)
- [UI kit 公开 API 与兼容策略](ui-kit-api.md)
- [仓库工具](../../../tools/README.md)
