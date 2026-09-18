# 进度（成就）系统

本文档是模组进度（原版 Advancement）子系统的唯一权威文档，覆盖 `achievement/` 包、`IAchievementHelper` 平台抽象、进度 JSON 的组织方式与授予判定。玩法侧收集进度的判定范围与考古笔记状态强耦合，相关机制见 [考古笔记系统](journal.md)；文本与本地化键规范见 [文本格式规范](tooltip.md)。

## 1. 职责概述

- **进度条目**：全部为手写 JSON，位于 `common/src/main/resources/data/unsuspiciousblock/advancement/`。不使用 datagen。
- **授予桥**：游戏逻辑不直接调用原版 Advancement API，一律经 `achievement/AchievementManager` → `IAchievementHelper` → 原版 API。
- **两类授予方式**：能用原版触发器表达的用数据包条件（`inventory_changed` / `recipe_crafted`）；需要读模组内部状态的用 `minecraft:impossible` + 代码授予。
- **补发**：玩家加入世界时全量重检一遍需要代码判定的收集类进度，避免事件漏触发或目录尚未就绪导致漏授予。

## 2. 数据流

```
游戏逻辑（方块实体 / 物品 / mixin / 笔记追踪）
        │
        ▼
AchievementManager.grantIfNotAlready(player, ModAchievements.XXX)
        │  静态桥，未注入平台实现时静默返回
        ▼
IAchievementHelper  ──►  VanillaAchievementHelper（common/platform，无平台差异）
        │                  server.getAdvancements().get(id)
        ▼                  player.getAdvancements().award(holder, criterion)
原版 Advancement 系统
```

收集类进度是另一条支线：战利品解锁事件与玩家登录都把考古笔记状态交给 `ArchaeologyChallengeChecker`，由它读玩家日记自行判定，命中后同样经 `AchievementManager` 授予。

## 3. 关键类

| 类 | 职责 |
|---|---|
| `achievement/ModAchievement` | 进度元数据接口：`path()` / `criterion()` / `id()` |
| `achievement/ModAchievements` | 全部需要**代码授予**的进度枚举，共 10 项，给出 advancement 路径与 criterion 键 |
| `achievement/AchievementManager` | 静态桥：`init` / `grant` / `has` / `grantIfNotAlready`；由 `UnsuspiciousBlockCommon` 初始化时注入平台实现 |
| `platform/services/IAchievementHelper` | 平台抽象 SPI：`grant` / `has` / `revoke` |
| `platform/VanillaAchievementHelper` | 唯一实现，直接使用原版 API——**该 API 在 Fabric 与 NeoForge 上完全一致，因此不需要按平台分叉** |
| `journal/tracking/ArchaeologyChallengeChecker` | 三项考古收集类进度的判定器，含实时检测与登录补发两个入口 |

`ModAchievements` 枚举**只收录需要代码授予的进度**（10 项）。纯触发器驱动的进度（`eye_of_cat`、`it_belongs_in_a_museum`、`see_through_the_sand`）不在此枚举内，它们由数据包条件自行完成。

## 4. 进度清单与授予方式

`adventure/` 下 10 条，`challenges/` 下 3 条，共 13 条手写进度。按触发器统计：7 条 `minecraft:impossible`（代码授予）、4 条 `inventory_changed`、2 条 `recipe_crafted`。

```
take_note_take_note（根：合成考古笔记）
├─ paper_trail（获得失落书页）
│   └─ ancient_scholarship（goal：锻造附魔书）
├─ penny_for_your_finds（获得古代金币）
│   └─ see_through_the_sand（合成解析仪）
│       ├─ cache_me_if_you_can（challenge：单表日志条目达上限）
│       └─ motherlode（challenge · hidden：一次范围扫描扫出 ≥8 个对象）
├─ eye_of_cat（获得猫之瞳）
├─ it_belongs_in_a_museum（获得标本箱）
├─ round_and_round（陶轮台首次工作）
├─ sherd_collector（goal：集齐原版陶片）
├─ template_collector（goal：集齐原版纹饰样板）
└─ completionists_dust（challenge：原版考古表全图鉴）
```

代码授予的调用点：

| 进度 | 调用点 | 判定 |
|---|---|---|
| `round_and_round` | `PotteryWheelBlockEntity` | 陶轮台首次**开始工作**（不是合成或放置），授予最近一次操作者 |
| `motherlode` | `SuspiciousReaderItem` | 单次范围扫描结果 ≥8 个对象 |
| `ancient_scholarship` | `SmithingMenuMixin` | 锻造台产出附魔书 |
| `cache_me_if_you_can` | `JournalLogHandler` | 任意单张表的日志条目数达到上限 |
| `sherd_collector` / `template_collector` / `completionists_dust` | `ArchaeologyChallengeChecker` | 见第 5 节 |

`advancement/recipes/unsuspiciousblock/` 下 7 条是**配方解锁进度**：手写，遵循原版 `minecraft:recipes/root` 父级 + `minecraft:recipe_unlocked` 触发器的标准写法，`rewards.recipes` 回填对应配方。它们**没有 `display` 段**，因此不出现在玩家的进度树里，只用于原版配方的解锁提示。上文按触发器统计的 13 条不含这些文件。

## 5. 收集类进度的判定范围

`ArchaeologyChallengeChecker` 的两条入口：战利品解锁事件（`LootTrackingBootstrap`）实时检测；玩家加入世界（`ArchaeologyJournalNetwork`）全量补发。

- **陶片 / 样板**：范围取自原版物品标签 `#minecraft:decorated_pot_sherds` 与 `#minecraft:trim_templates`，**仅保留 `minecraft` 命名空间条目**，其他模组向标签追加的内容一律忽略。标签在每次检测时通过 `BuiltInRegistries.ITEM.getOrCreateTag(...)` 读取，天然兼容数据包重载；**标签为空（尚未绑定）时跳过授予**，避免空集合被误判为「已集齐」。
- **集齐的判定基准**是考古日记（`ArchaeologyJournalState`）中的物品解锁记录，标签只决定「需要集齐哪些物品」。陶片与样板均为普通物品，用 `LootResultSignature.plain(itemId)` 匹配。
- **全图鉴**：判定范围为 `minecraft:archaeology/` 前缀的原版考古表，取 `ArchaeologyJournalServerCatalog.getRawCatalog()` 而不是渐进模拟后的目录，避免目录加载进度影响判定范围；要求每张表都已解锁且表中每件物品都至少获得过一次。至少存在一张原版考古表才可能达标，防止目录为空时误判。
- **性能**：三项进度都已获得时直接返回，不做任何集合计算。

## 6. 扩展点

### 6.1 新增进度

1. 在 `data/unsuspiciousblock/advancement/<adventure|challenges>/` 下新建 JSON，`parent` 指向父进度（根进度需要 `display.background`）。
2. 用原版触发器能表达就写在 `criteria.conditions` 里；否则写 `minecraft:impossible`，并按下一条补代码授予。
3. 需要代码授予时，在 `ModAchievements` 枚举追加一项（路径 + criterion 键，两者必须与 JSON 中的文件名和 criteria 键一致），在游戏逻辑里调用 `AchievementManager.grantIfNotAlready(...)`。
4. 语言文件补 `advancement.unsuspiciousblock.<文件名>`（标题）与 `advancement.unsuspiciousblock.<文件名>.desc`（描述），`en_us.json` 与 `zh_cn.json` 同步。

### 6.2 本地化文案约定

进度标题采用**中文意译，不直译**。这条约定是有意的：直译会把 `it_belongs_in_a_museum`、`round_and_round` 这类带双关的英文原名译得生硬，成品一律按中文语感重写（如「私人博物馆」「吱悠~吱悠~」「好记性不如烂笔头」）。新增进度时沿用同一口径，不要参照英文原文逐词对译。

### 6.3 注意

- `grantIfNotAlready` 是幂等的，重复触发安全；直接 `grant` 则不做检查。
- 使用 `minecraft:impossible` 的进度**只能由代码授予**，数据包或命令无法触发，新增时务必确认对应调用点已接上。
- 移动或改名已有进度会让玩家已解锁的该进度失效（无迁移）。删除进度条目同理。

## 7. 相关文档

- [考古笔记系统](journal.md) — 收集类进度的判定依赖其玩家状态与目录
- [战利品表系统](loottable.md) — 目录范围、签名与全图鉴判定范围
- [平台抽象](platform-abstraction.md) — `IAchievementHelper` 所在的 SPI 机制
- [文本格式规范](tooltip.md) — 本地化键命名与文本结构
