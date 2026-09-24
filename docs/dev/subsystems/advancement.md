# 进度（成就）系统

> 手写进度 JSON + 代码授予桥，覆盖 `achievement/` 包、`IAchievementHelper` 平台抽象与收集类判定。
> 玩家可见入口：原版进度树（`Esc` → 进度），以及配方解锁提示。
> 本文件是进度子系统的唯一权威；收集类进度依赖的玩家状态与目录见 [考古笔记系统](journal.md)，文本与本地化键规范见 [文本格式规范](../foundation/text-format.md)。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 进度数据 | `common/src/main/resources/data/unsuspiciousblock/advancement/`（`adventure/` 10 条、`challenges/` 3 条、`recipes/unsuspiciousblock/` 7 条配方解锁） |
| 授予桥 | `achievement/AchievementManager.java`（静态桥）、`achievement/ModAchievements.java`（枚举清单）、`achievement/ModAchievement.java`（元数据接口） |
| 平台 SPI | `platform/services/IAchievementHelper.java`；实现 `platform/VanillaAchievementHelper.java`（common 自实现，**双平台共用**） |
| 收集类判定 | `journal/tracking/ArchaeologyChallengeChecker.java` |
| 授予调用点 | `blockentity/PotteryWheelBlockEntity.java:262`、`item/SuspiciousReaderItem.java:327`、`mixin/container/SmithingMenuMixin.java:48`、`network/journal/JournalLogHandler.java:93`、`ArchaeologyChallengeChecker.java` |
| 网络 | 无（走原版进度同步） |
| Mixin | `mixin/container/SmithingMenuMixin.java`（仅用于 `ancient_scholarship` 的授予；全清单见 [Mixin 总览](../foundation/mixin.md)） |
| 平台差异 | 无。原版 Advancement API 在 Fabric 与 NeoForge 上完全一致，因此不需要按平台分叉 |

## 2. 数据流

```
游戏逻辑（方块实体 / 物品 / mixin / 笔记追踪）
        │
        ▼
AchievementManager.grantIfNotAlready(player, ModAchievements.XXX)
        │  静态桥；未注入平台实现时静默返回
        ▼
IAchievementHelper ──► VanillaAchievementHelper（common/platform，无平台差异）
        │                  server.getAdvancements().get(id)
        ▼                  player.getAdvancements().award(holder, criterion)
原版 Advancement 系统

收集类进度是另一条支线：
战利品解锁事件 / 玩家登录
        └─► ArchaeologyChallengeChecker（读玩家日记自行判定）
              └─► AchievementManager.grantIfNotAlready(...)
```

`AchievementManager.init(new VanillaAchievementHelper())` 由 `UnsuspiciousBlockCommon.init()` 调用（见 [架构总览](../foundation/architecture.md)）。

## 3. 关键类

| 类 | 职责 |
|---|---|
| `achievement/ModAchievement` | 进度元数据接口：`path()` / `criterion()` / `id()` |
| `achievement/ModAchievements` | 全部需要**代码授予**的进度枚举，共 10 项，给出 advancement 路径与 criterion 键 |
| `achievement/AchievementManager` | 静态桥：`init` / `grant` / `has` / `grantIfNotAlready` |
| `platform/services/IAchievementHelper` | 平台抽象 SPI：`grant` / `has` / `revoke` |
| `platform/VanillaAchievementHelper` | 唯一实现，直接用原版 API |
| `journal/tracking/ArchaeologyChallengeChecker` | 三项考古收集类进度的判定器，含实时检测与登录补发两个入口 |

`ModAchievements` 枚举**只收录需要代码授予的进度**（10 项）。纯触发器驱动的进度（`eye_of_cat`、`it_belongs_in_a_museum`、`see_through_the_sand`）不在此枚举内，由数据包条件自行完成。

## 4. 进度清单

`adventure/` 下 10 条、`challenges/` 下 3 条，共 13 条手写进度。按触发器统计：7 条 `minecraft:impossible`（代码授予）、4 条 `inventory_changed`、2 条 `recipe_crafted`。另有 `recipes/unsuspiciousblock/` 下 7 条配方解锁进度，**没有 `display` 段**，不出现在进度树，只用于原版配方解锁提示，不计入上述 13 条。

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
| `sherd_collector` / `template_collector` / `completionists_dust` | `ArchaeologyChallengeChecker` | 见下节 |

### 收集类进度的判定范围

`ArchaeologyChallengeChecker` 的两条入口：战利品解锁事件（`LootTrackingBootstrap`）实时检测；玩家加入世界（`ArchaeologyJournalNetwork`）全量补发。

- **陶片 / 样板**：范围取自原版物品标签 `#minecraft:decorated_pot_sherds` 与 `#minecraft:trim_templates`，**仅保留 `minecraft` 命名空间条目**，其他模组向标签追加的内容一律忽略。标签在每次检测时通过 `BuiltInRegistries.ITEM.getOrCreateTag(...)` 读取，天然兼容数据包重载；**标签为空（尚未绑定）时跳过授予**，避免空集合被误判为「已集齐」。
- **集齐基准**是考古日记（`ArchaeologyJournalState`）中的物品解锁记录，标签只决定「需要集齐哪些物品」。陶片与样板均为普通物品，用 `LootResultSignature.plain(itemId)` 匹配。
- **全图鉴**：范围为 `minecraft:archaeology/` 前缀的原版考古表，取 `ArchaeologyJournalServerCatalog.getRawCatalog()` 而不是渐进模拟后的目录，避免目录加载进度影响判定范围；要求每张表都已解锁且表中每件物品都至少获得过一次。至少存在一张原版考古表才可能达标。
- **性能**：三项都已获得时直接返回，不做任何集合计算。

## 5. 扩展点：新增进度

1. 在 `data/unsuspiciousblock/advancement/<adventure|challenges>/` 下新建 JSON，`parent` 指向父进度（根进度需要 `display.background`）。
2. 用原版触发器能表达就写在 `criteria.conditions` 里；否则写 `minecraft:impossible`，并按下一条补代码授予。
3. 需要代码授予时，在 `ModAchievements` 枚举追加一项（路径 + criterion 键，**必须与 JSON 中的文件名和 criteria 键一致**），在游戏逻辑里调用 `AchievementManager.grantIfNotAlready(...)`。
4. 语言文件补 `advancement.unsuspiciousblock.<文件名>`（标题）与 `advancement.unsuspiciousblock.<文件名>.desc`（描述），`en_us.json` 与 `zh_cn.json` 同步。

### 新增收集类判定

在 `ArchaeologyChallengeChecker` 加一条判定分支，调用点复用现有两个入口（战利品解锁事件 + 玩家登录补发），不要另建触发路径。

## 6. 约束与陷阱

- `grantIfNotAlready` 幂等，重复触发安全；直接 `grant` 不做检查。
- 使用 `minecraft:impossible` 的进度**只能由代码授予**，数据包或命令无法触发；新增时务必确认调用点已接上。
- 移动或改名已有进度会让玩家已解锁的该进度失效（无迁移）；删除条目同理。
- 进度标题采用**中文意译，不直译**。这是有意约定：直译会把 `it_belongs_in_a_museum`、`round_and_round` 这类双关原名译得生硬，成品一律按中文语感重写（如「私人博物馆」「吱悠~吱悠~」「好记性不如烂笔头」）。新增时沿用同一口径，不要逐词对译。
- 新增 / 修改任何游戏内文本（含进度标题）前先读 [文本格式规范](../foundation/text-format.md)。

## 7. 相关文档

- [考古笔记系统](journal.md) —— 收集类进度的判定依赖其玩家状态与目录
- [战利品表系统](loottable.md) —— 目录范围、签名与全图鉴判定范围
- [平台抽象](../foundation/platform-spi.md) —— `IAchievementHelper` 所在的 SPI 机制
- [文本格式规范](../foundation/text-format.md) —— 本地化键命名与文本结构
