# 工具栏图标图集

本文档记录 `assets/unsuspiciousblock/textures/gui/toolbar_icons.png` 的固定槽位映射，供 [`JournalControls`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalControls.java)（通过 UI Kit `UiIconSheet` 按行列切片）与 [`PotteryWheelScreen`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/screen/PotteryWheelScreen.java) 等界面组件统一取样。

## 1. 图集规格

| 项目 | 规格 |
|---|---|
| 图集尺寸 | `81 x 27 px` |
| 单个图标尺寸 | `9 x 9 px` |
| 网格布局 | `9 列 x 3 行` |
| 坐标原点与编号 | 左上角为 `(0, 0)`，行与列均从 `0` 开始编号 |
| UV 计算公式 | `u = column * 9`，`v = row * 9` |

渲染单个图标时，传入纹理总尺寸 `81 x 27`，按上述公式切取 `9 x 9` 区域。

## 2. 槽位映射

| 行 | 列 | UV | `JournalControls.Icon` 枚举名 | 图标含义 |
|---:|---:|---:|---|---|
| 0 | 0 | `(0, 0)` | `ARROW_UP` | 上箭头，正序 |
| 0 | 1 | `(9, 0)` | `ARROW_DOWN` | 下箭头，倒序 |
| 0 | 2 | `(18, 0)` | `SHOW_LOCKED` | 睁开的眼睛，显示锁定条目 |
| 0 | 3 | `(27, 0)` | `HIDE_LOCKED` | 闭上的眼睛，隐藏锁定条目 |
| 0 | 4 | `(36, 0)` | `SEARCH` | 放大镜，搜索 |
| 0 | 5 | `(45, 0)` | `CLOSE` | 叉号，关闭或清除 |
| 0 | 6 | `(54, 0)` | — | 未使用 |
| 0 | 7 | `(63, 0)` | `PENCIL` | 铅笔，编辑或备注状态 |
| 0 | 8 | `(72, 0)` | `CLOCK` | 时钟，时间 |
| 1 | 0 | `(0, 9)` | `SORT_DEFAULT` | 三道杠，默认排序 |
| 1 | 1 | `(9, 9)` | `SORT_NAME` | 按首字母排序 |
| 1 | 2 | `(18, 9)` | `SORT_ITEM_COUNT` | 按数量排序 |
| 1 | 3 | `(27, 9)` | `SORT_FAVORITE` | 星星，收藏 |
| 1 | 4 | `(36, 9)` | `SORT_UNLOCK` | 打开的锁，按解锁状态排序 |
| 1 | 5 | `(45, 9)` | `SORT_UPDATE_TIME` | 沙漏，按更新时间排序 |
| 1 | 6 | `(54, 9)` | `DISABLED` | 关闭的锁，禁用态（`PotteryWheelScreen` 模式按钮直接取样该槽位） |
| 1 | 7 | `(63, 9)` | `ENABLED` / `CHECK` | 对钩；两枚举常量共用该槽位，`PotteryWheelScreen` 启用态直接取样该槽位 |
| 1 | 8 | `(72, 9)` | — | 未使用 |
| 2 | 0 | `(0, 18)` | `GEAR` | 齿轮，设置或管理 |
| 2 | 1 | `(9, 18)` | `SQUARE` | 方框，批量选择 |
| 2 | 2 | `(18, 18)` | `GROUP` | 四个矩形，分组 |
| 2 | 3 | `(27, 18)` | `TRASH_FULL` | 装满的垃圾桶 |
| 2 | 4 | `(36, 18)` | `TRASH_EMPTY` | 空的垃圾桶 |
| 2 | 5 | `(45, 18)` | `BIOME` | 树，群系 |
| 2 | 6 | `(54, 18)` | `DIMENSION` | 线框立方体，维度 |
| 2 | 7 | `(63, 18)` | — | 预留 |
| 2 | 8 | `(72, 18)` | — | 预留 |

> `PotteryWheelScreen` 的控制模式按钮不经过 `JournalControls.Icon` 枚举：禁用态与启用态直接使用 `UiIcon.Sprite` 取样第 1 行第 6 / 7 列（指定纹理尺寸 `81 x 27`），红石启用态另使用原版 `Items.REDSTONE` 物品图标。

## 3. 使用约束

- **新增图标仅追加至预留或未使用槽位**：禁止调换已有槽位坐标，避免破坏现有按钮外观。
- **使用行列坐标而非散落硬编码 UV**：业务控件通过 `JournalControls.Icon` 或显式 `(col * 9, row * 9)` 取样。
- **语义与文案分离**：图标可跨按钮复用，每个按钮必须提供独立的本地化 Tooltip 与无障碍名称。
- **像素采样**：保持像素风格最近邻采样，避免 `9 x 9` 图标边缘模糊。

## 4. 日志页面操作映射

日志工具栏按钮与快捷操作统一通过 `JournalControls` 取样图集（`LogDetailPanel` 与 `ArchaeologyJournalScreen` 的返回按钮仍使用文本字符 `'←'` 构造）：

| 操作 | `JournalControls.Icon` 枚举 |
|---|---|
| 排序方向 | `ARROW_UP` / `ARROW_DOWN` |
| 按时间 / 维度 / 群系 / 备注状态分组 | `CLOCK` / `DIMENSION` / `BIOME` / `PENCIL` |
| 日志条目保留配置 | `GEAR` |
| 清除当前表 / 清除所有日志 | `TRASH_EMPTY` / `TRASH_FULL` |
| 进入与退出批量选择 / 确认批量操作 | `SQUARE` / `GROUP` / `CHECK` |
| 编辑日志备注 | `PENCIL` |

## 5. 相关资源

书本侧边标签贴图 `textures/gui/book_side_tabs/`（帮助 / 管理，尺寸 `24 x 20`）不属于本图集，由 [`draw_book_side_tabs.py`](../../../tools/draw_book_side_tabs.py) 生成，修改后需重新运行该脚本。

## 6. 相关文档

- [客户端与 GUI](../subsystems/client-ui.md)
- [笔记 GUI 内部机制](journal-ui-internals.md)
