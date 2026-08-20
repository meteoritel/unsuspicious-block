# 进度树完善规划


## 一、现状与目标

现状：

- 进度全部手写在 `common/src/main/resources/data/unsuspiciousblock/advancement/`（1.21 单数路径），无 datagen。
- 共 10 条玩法进度 + 5 条配方解锁进度；简单条件用原版触发器（`recipe_crafted` / `inventory_changed`），复杂判定用 `minecraft:impossible` + `AchievementManager.grant` 代码授予，登录时有补发防漏机制。
- 缺口：工具线（解析仪/考古铲）、陶轮制陶、标本箱（枚举中已有 TODO 预留位）均无进度覆盖；`zh_cn.json` 中 9 个进度 title 为英文原文。

目标：

- 补全工具线、陶轮线、标本箱线进度，删除 `unsuspicious_minds`。
- `zh_cn.json` 全部进度 title 统一为中文**意译**（不直译）。
- 维持手写 JSON + 代码授予的现有架构，不引入 datagen。

**明确不做**（本轮范围外）：

- 猫族关系线进度（内容仍在开发中，等模型与玩法稳定后再做；见 `docs/roadmap.md` 猫进度组规划）。
- 不可疑方块进度（整蛊玩具性质，不进树）。
- 附魔收集进度（`paper_trail` + `ancient_scholarship` 已覆盖"获得失落书页 -> 锻造附魔书"主线，不细化到逐附魔）。

## 二、最终树形

```
take_note_take_note（根：合成考古笔记）
├─ paper_trail（获得失落书页）
│   └─ ancient_scholarship（goal：锻造附魔书）
├─ penny_for_your_finds（获得古代金币）
│   └─ see_through_the_sand ★新（合成解析仪）
│       ├─ cache_me_if_you_can（challenge 500XP：单表日志条目达上限）
│       └─ motherlode ★新（challenge 无XP · hidden：一次范围扫描扫出 ≥8 个对象）
├─ eye_of_cat（获得猫之瞳）
├─ it_belongs_in_a_museum ★新（获得标本箱）
├─ round_and_round ★新（陶轮台首次工作）
├─ sherd_collector（goal：集齐陶片）
├─ template_collector（goal：集齐样板）
└─ completionists_dust（challenge 500XP：全表全物品）
```

★新 = 本轮新增（4 条）；`unsuspicious_minds` **删除**（原挂在 penny_for_your_finds 下，其子级 cache_me_if_you_can 与新隐藏进度改挂"合成解析仪"下）。

## 三、新增进度明细

| 项 | see_through_the_sand | round_and_round | it_belongs_in_a_museum | motherlode |
|---|---|---|---|---|
| 文件 | `advancement/adventure/see_through_the_sand.json` | `advancement/adventure/round_and_round.json` | `advancement/adventure/it_belongs_in_a_museum.json` | `advancement/challenges/motherlode.json` |
| 父级 | `unsuspiciousblock:adventure/penny_for_your_finds` | `unsuspiciousblock:adventure/take_note_take_note` | `unsuspiciousblock:adventure/take_note_take_note` | `unsuspiciousblock:adventure/see_through_the_sand` |
| 触发器 | `minecraft:recipe_crafted`（解析仪配方，纯 JSON） | `minecraft:impossible`（代码授予） | `minecraft:inventory_changed`（标本箱物品，纯 JSON） | `minecraft:impossible`（代码授予） |
| 框架 | task | task | task | challenge，`"hidden": true`，**无 XP 奖励** |
| 中文 title | 拨沙见真 | 吱悠~吱悠~ | 私人博物馆 | 满载而归 |
| 英文 title | See Through the Sand | Round and Round | It Belongs in a Museum! | Motherlode |
| 中文 desc | 合成一台可疑解析仪。 | 纹饰陶轮台首次开始工作。 | 获得一个标本箱，给收藏安个家。 | 一次范围扫描中扫出至少 8 个对象。 |
| 英文 desc | Craft a Suspicious Reader. | A decorated pot wheel starts spinning for the first time. | Obtain a specimen box and give your collection a home. | Reveal at least 8 objects in a single ranged scan. |
| 图标 | 可疑解析仪物品 | 纹饰陶轮台方块物品（实施时从注册表核对 id） | 标本箱物品 | 古代金币（或解析仪，实施时定） |

技术要点：

- `hidden` 字段为 1.21.1 原版 `display` 支持的字段（`"display": {"hidden": true}`），解锁前在进度界面完全不可见，无需额外代码。
- `it_belongs_in_a_museum` 沿用 `ModAchievements` 枚举中已有的预留 id；因其改为纯 JSON 触发，**枚举中的 TODO 预留条目应删除**（无需代码授予）。
- `motherlode` 的"对象"定义：单次范围扫描结果中的**可疑方块 + 战利品箱**，合计 ≥8 即授予。
- 图标已核对：陶轮台为 `unsuspiciousblock:pottery_wheel`，标本箱为 `unsuspiciousblock:specimen_box`，`motherlode` 图标取古代金币。

## 三点五、补充要求：收集类进度改为原版标签判定

`sherd_collector` 与 `template_collector` 的判定范围由**硬编码固定物品集合**改为原版物品标签：

- `sherd_collector`：要求集齐 `#minecraft:decorated_pot_sherds` 标签下的所有陶片。
- `template_collector`：要求集齐 `#minecraft:trim_templates` 标签下的所有纹饰样板。

实现要点（`ArchaeologyChallengeChecker`）：

- 通过 `BuiltInRegistries.ITEM.getOrCreateTag(ItemTags.DECORATED_POT_SHERDS / TRIM_TEMPLATES)` 在每次检测时读取标签内容，天然兼容数据包重载。
- **仅保留 `minecraft` 命名空间条目**，其他模组向标签添加的内容一律忽略。
- 标签为空（尚未绑定）时跳过授予，避免空集合被 `allCollected` 误判为"已集齐"。
- 收集判定基准不变：仍以考古日记（`ArchaeologyJournalState`）中的物品解锁记录为准；标签仅改变"需要集齐哪些物品"的范围。
- 注意：`template_collector` 的范围因此从 4 种考古样板扩展到标签下全部原版样板（含下界合金升级、各结构样板等），多数需通过战利品容器（日志同样追踪 LOOT_CONTAINER 来源）解锁。
- 两语言文件中这两条进度的 desc 同步改为标签口径文案。

## 四、改动清单（按文件）

### 4.1 进度 JSON

1. **新增** 4 个 JSON（见上表），父级引用注意带命名空间 `unsuspiciousblock:`。
2. **修改** `advancement/challenges/cache_me_if_you_can.json`：`parent` 由 `unsuspiciousblock:story/unsuspicious_minds` 改为 `unsuspiciousblock:adventure/see_through_the_sand`。
3. **删除** `advancement/story/unsuspicious_minds.json`（`story/` 目录随之清空可一并删除）。
4. `advancement/recipes/` 下 5 条配方解锁进度**不动**（`suspicious_reader` 的配方解锁进度与玩法进度 `see_through_the_sand` 是两回事，保留）。

### 4.2 Java 代码

1. `achievement/ModAchievements.java`：
   - 移除 `UNSUSPICIOUS_MINDS` 枚举条目；
   - 移除 `IT_BELONGS_IN_A_MUSEUM` 的 TODO 预留条目（已改为纯 JSON）；
   - 新增 `ROUND_AND_ROUND("adventure/round_and_round", ...)` 与 `MOTHERLODE("challenges/motherlode", ...)`。
2. `SuspiciousReaderItem.java`（约 474 行）：移除 `unsuspicious_minds` 的授予调用；在**范围扫描完成**处新增统计逻辑——扫描结果中可疑方块 + 战利品箱 ≥8 时 `grantIfNotAlready(MOTHERLODE)`（阈值常量 `MOTHERLODE_MIN_OBJECTS = 8`）。
3. 陶轮台方块逻辑（`PotteryWheelBlockEntity` + `PotteryWheelMenu`）：陶轮台无直接玩家引用，采用"最近操作者"方案--菜单服务端构造时把打开者登记到 BlockEntity（内存字段，不持久化），`serverTick` 中加工进度从 0 递增的第一个 tick 视为"开始工作"，向最近操作者 `grantIfNotAlready(ROUND_AND_ROUND)`；无人操作过（纯漏斗供料）则不授予。
4. `ArchaeologyChallengeChecker.java`：按"三点五"节要求把陶片/样板固定集合改为标签判定。
5. 登录补发机制**不适用**于这两条新进度：`round_and_round` 与 `motherlode` 依赖瞬时动作（陶轮首次工作、单次扫描统计），无持久化状态可查，错过不补发（玩家可再次触发）。此为已确认的取舍，不需要写 `checkAndGrantAll`。

### 4.3 语言文件（`en_us.json` 与 `zh_cn.json` 同步）

1. **新增** 4 条进度 × (title + desc) = 8 条（两种语言各 8 条，文案见上表）。
2. **修改** 8 个 title 为中文意译（zh_cn.json 第 430~447 行区域）：

   | 进度 | 现值 | 改为       |
   |---|---|----------|
   | take_note_take_note | Take Note, Take Note！ | 好记性不如烂笔头 |
   | paper_trail | Paper Trail | 沙底遗篇     |
   | penny_for_your_finds | Penny for Your Finds | 沙里淘金     |
   | ancient_scholarship | Ancient Scholarship | 故纸堆里的学问  |
   | sherd_collector | Sherd Collector | 陶片收藏家    |
   | template_collector | Template Collector | 纹饰收藏家    |
   | completionists_dust | Completionist's Dust | 尘埃落定     |
   | cache_me_if_you_can | Cache Me If You Can | 记录大师     |

   （`eye_of_cat` 已是中文"猫之眼"，不动；desc 文本均已是中文，不动；en_us.json 的英文 title 全部不动。）
3. **删除** `unsuspicious_minds` 的 title + desc 两条（两种语言）。

## 五、验收标准

1. `./gradlew build` 双平台编译通过（等待不少于 120 秒）。
2. 游戏内验证（Fabric / NeoForge 各一次，交由用户测试）：
   - 树形与本文档第二节一致；`unsuspicious_minds` 不再出现。
   - `motherlode` 在进度界面不可见，用解析仪一次范围扫描扫出 ≥8 个对象后弹出 challenge 提示（无经验奖励）。
   - 陶轮台首次工作时弹出"吱悠~吱悠~"。
   - 获得标本箱（村庄武器匠箱等途径）即触发"私人博物馆"。
   - 中文界面下所有进度 title 均为中文。

## 六、风险与兼容性说明

- **旧存档**：已获得 `unsuspicious_minds` 的玩家该进度直接消失（无迁移）；已获得的 `cache_me_if_you_can` 不受父级变更影响，保持已解锁状态。
- **图标物品 id**：标本箱、陶轮台的确切注册 id 实施时从注册表核对，本文档不预设。
- **陶轮"首次工作"的判定点**：实施时需阅读陶轮台方块实体逻辑，选择"开始工作"而非"合成放置"作为触发点（与 desc 文案一致）。
- **≥8 对象的统计口径**：以单次范围扫描的扫描结果列表为准；若实现中发现扫描结果不含战利品箱分类，需回到本文档修订定义。
