# 战利品表系统

本文档描述 `loottable/` 包的架构：如何解析、收录、注入战利品表，如何为物品生成稳定签名，以及如何通过模拟抽取估算概率。这是考古笔记目录的底层支撑。

## 1. 职责概述

`loottable/` 包不直接管理玩家进度，而是提供五个底层能力：

1. **目录数据结构**：定义 `TableDefinition` / `ItemDefinition` 等跨模块共享的记录类型。
2. **签名机制**：为战利品结果生成稳定签名，用于进度匹配与持久化。
3. **收录范围匹配**：把配置规则解析为可匹配的模式，判断哪些战利品表被纳入目录。
4. **概率模拟**：对每张表执行大量模拟抽取，统计物品出现概率。
5. **战利品注入**：平台相关的注入钩子，确保模组注入的物品被纳入概率统计。

## 2. 子包结构

```
loottable/
├── catalog/      目录数据结构与收录匹配
│   ├── LootTableCatalog            TableDefinition / ItemDefinition 等记录类型
│   ├── LootTablePattern            收录规则解析与匹配
│   ├── LootTableNames              表名/本地化 key 工具
│   ├── LootTableTranslationStore      服务端按世界保存自定义表名
│   ├── MissingTranslationKeyExporter  客户端语言覆盖文件读写与缺失 key 补全
│   └── (ArchaeologyJournalCatalog 在 journal/catalog/，调用本包)
├── analysis/     战利品表 JSON 解析
│   ├── LootTableJsonParser         解析 loot_table JSON 为 TableDefinition
│   ├── LootConditionHandler(s|Info)  战利品条件分析与描述
│   ├── LootFunctionHandler(s)      战利品函数分析
│   └── LootParseUtil               解析工具
├── signature/    结果签名
│   ├── LootResultSignature         签名 record（核心）
│   ├── LootResultMatcher           运行时掉落与候选签名匹配
│   └── LootCounts                  计数工具
├── simulation/   概率模拟
│   ├── LootProbabilitySimulator    模拟引擎（单表 10000 次抽取）
│   ├── LootProbabilitySimulationWorker  主线程 tick 驱动器
│   ├── LootContextParamFiller      模拟用 LootParams 构建（宽松回退）
│   ├── SimulationFakePlayer        模拟用假玩家
│   └── ProbabilityFormat           概率格式化
├── injection/    战利品注入
│   ├── ArchaeologyLootInjector     注入器接口
│   └── ArchaeologyLootInjectors    全局注册器（单例）
└── condition/    自定义战利品条件
    ├── ModLootConditions           条件类型注册
    └── MudDredgingCondition         泥底打捞条件
```

## 3. 目录数据结构

[`LootTableCatalog`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTableCatalog.java) 定义跨模块共享的记录类型，供 `journal`、`network`、`client`、`command` 等包统一引用：

```
TableDefinition{
  id: ResourceLocation,          表 ID
  displayName: Component,        展示名
  type: String,                  声明类型
  items: List<ItemDefinition>,   物品条目
  simulationCount: int,          模拟次数（0 表示未模拟）
  childTables: List<ResourceLocation>  引用的子表
}

ItemDefinition{
  id, displayName, tooltipHint,
  probability: String,           概率字符串（"?"/"<0.01%"/"12.34%"）
  signature: LootResultSignature,物品签名
  acquisitionPaths: List<LootAcquisitionPath>,  获取路径
  injected: boolean              是否模拟期发现的注入条目
}

LootAcquisitionPath{
  sourceChildTable,    来自哪个子表
  sourceItemTag,       来自哪个 item tag
  entryConditions,     条目自身条件
  inheritedConditions  继承自 pool/组合 entry/表引用的条件
}
```

`acquisitionPaths` 记录一个物品在表中的所有获取路径（可能来自不同 pool、不同子表、不同条件），用于在 UI 中展示"如何获得"。`injected=true` 表示该条目不在原始 JSON 中，而是模拟期由 GLM 或 LootTableEvents.MODIFY 注入发现的。

## 4. 签名机制

[`LootResultSignature`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/signature/LootResultSignature.java) 是进度匹配与持久化的核心--它让"目录中的物品"与"玩家实际获得的物品"能对应起来。

### 4.1 签名类型

```
record LootResultSignature(ResourceLocation itemId, SignatureType type, @Nullable String data)
```

| SignatureType | 含义 | 构造场景 |
|---|---|---|
| `PLAIN` | 普通物品，无组件差异 | 无特殊组件的物品 |
| `COMPONENT_EXACT` | 严格组件签名 | 有 DataComponentPatch 的物品（编码为 JSON 字符串） |
| `ENCHANTED_APPROX` | 附魔近似 | 附魔物品（不区分具体附魔，避免签名爆炸） |
| `ENCHANTED_RANDOM` / `ENCHANTED_LEVEL` | 旧版附魔签名 | 仅作兼容别名，加载时折叠为 `ENCHANTED_APPROX` |
| `APPROX_ITEM_ONLY` | 近似回退 | 组件编码失败等异常情况 |

### 4.2 稳定存储键

`toStoredKey()` 生成稳定字符串，用于 NBT 持久化、网络传输与 Map key：

```
usb_sig|TYPE|itemId|base64(data)
```

`fromStoredKey()` 反向解析，并**兼容旧版**：不以 `usb_sig|` 开头的 key 视为旧版仅存 item id 的格式，转为 `PLAIN` 签名。这保证了玩家旧存档不丢失。

### 4.3 附魔判定的陷阱

`isActuallyEnchanted(stack)` 不能用 `stack.has(ENCHANTMENTS)`，因为工具/武器默认带有**空的** `ENCHANTMENTS` 组件，`has()` 会误返回 true。正确做法是检查 `stack.isEnchanted()` 或 `STORED_ENCHANTMENTS` 非空（附魔书场景）。

### 4.4 预览栈

`createPreviewStack()` 根据签名重建用于 UI 展示或精确匹配的物品栈：`COMPONENT_EXACT` 应用组件 patch，附魔变体设置附魔光效覆盖。

## 5. 收录范围匹配

[`LootTablePattern`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTablePattern.java) 把配置字符串解析为可匹配的规则：

| 语法 | 含义 | 示例 |
|---|---|---|
| `namespace:path` | 限定命名空间 | `minecraft:gameplay/fishing` |
| `path`（裸） | 匹配所有命名空间 | `archaeology/` |
| path 以 `/` 结尾 | 前缀匹配 | `archaeology/` 命中所有考古表 |
| path 不以 `/` 结尾 | 精确匹配 | `minecraft:chests/buried_treasure` |

默认规则定义在 [`ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ILootTableConfig.java)：

```java
List.of(
    "archaeology/", "archeology/", "pots/",
    "minecraft:gameplay/fishing",
    "minecraft:chests/buried_treasure",
    "minecraft:chests/ancient_city", "minecraft:chests/ancient_city_ice_box",
    "unsuspiciousblock:gameplay/fishing/",
    "unsuspiciousblock:gameplay/fossil_hunter/"
)
```

命中规则的表被纳入目录，并通过引用闭包递归发现子表（见 [`docs/journal-categories.md`](../journal-categories.md) 的"收录根表"与"引用闭包"概念）。`stripFrom` 剥离命中前缀，用于生成本地化 key 与展示名。

### 5.1 可视化追踪管理

管理页面的候选集合以服务端 `ReloadableServerRegistries` 中 `Registries.LOOT_TABLE` 的 key 为唯一权威，不读取客户端资源列表，也不猜测不存在的表。当前明确排除 path 以 `entities/`、`blocks/` 开头的实体和方块掉落表。

追踪规则仍支持前缀和精确 ID。为允许从宽泛前缀中移除单表，`excluded_loot_tables` 保存精确排除项：关闭一张被前缀命中的表时加入排除项；重新开启时移除排除项，若原规则未命中则把精确 ID 加入追踪规则。修改权限要求服务端权限等级 2，变更后重建目录并广播最新管理快照。

### 5.2 自定义表名

名称 key 始终由 `LootTableNames.createTranslationKey(ResourceLocation)` 自动生成。服务端把各语言名称保存到当前世界的 `serverconfig/unsuspiciousblock-loot-table-names.json`，并随管理快照派发给所有在线客户端。空值是删除标记，用于清除客户端已保存的旧名称。

客户端把收到的名称合并到全局 `config/unsuspiciousblock/lang/<language>.json`，同一客户端下所有存档共用。`ClientLanguageMixin` 只提供语言加载入口，具体读写与合并由 `ClientLootTableLanguageStore` 和 `MissingTranslationKeyExporter` 完成；覆盖范围限制为自动生成的战利品表名称 key。缺失 key 自动补全也写入当前语言的同一文件。

## 6. 解析流程

[`LootTableJsonParser`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootTableJsonParser.java) 解析 loot_table JSON 为 `TableDefinition`：

- 遍历 pools / entries，提取物品、条件、函数。
- `LootConditionHandler` / `LootFunctionHandler` 把原版条件/函数转译为可读的 `LootConditionInfo`（含描述与概率提示），供 UI 展示。
- 识别 `loot_table` 类型 entry（嵌套表引用），建立父子关系。
- 此阶段概率字段为 `"?"` 占位符，等待模拟填充。

解析结果交给 [`ArchaeologyJournalCatalog.load`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalCatalog.java)（在 `journal/catalog/` 包），构建完整目录与分类结构。解析细节见 [考古笔记系统](journal.md) 的目录构建部分。

## 7. 概率模拟

### 7.1 模拟引擎

[`LootProbabilitySimulator.simulateOne`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulator.java) 对单张表执行 `SIMULATION_COUNT = 10_000` 次模拟抽取：

```
simulateOne(tableId, rawTable, level)
  ├─ 取 LootTable（按声明的 paramSet 构建 LootParams，LootContextParamFiller 宽松回退）
  ├─ 初始化候选签名（来自 JSON 解析）+ appearanceCounts
  └─ 循环 10000 次：
       ├─ lootTable.getRandomItems(lootParams)
       ├─ ArchaeologyLootInjectors.get().maybeReplace(tableId, drops, random)
       │     Fabric 显式调用注入器；NeoForge 空实现（GLM 已在 getRandomItems 内部完成）
       └─ 对每个 drop：
            ├─ LootResultMatcher.resolve(stack, candidates) 匹配已知签名 -> 计数
            └─ 未匹配 -> deriveSignature 派生签名（附魔折叠为近似）
                 若已是已知签名 -> 保守跳过（歧义掉落）
                 否则登记为注入条目（injected=true）并计数
```

概率计算规则：

| 出现次数 | 条件 | 概率字符串 |
|---|---|---|
| 0 | `hasConditions` | `"?"`（条件性物品，模拟可能未覆盖） |
| 0 | 无条件 | `"<0.01%"` |
| >0 | - | `formatPercent(appearances / 10000)` |

### 7.2 主线程 tick 驱动

[`LootProbabilitySimulationWorker`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulationWorker.java) 是模拟的调度器。**关键约束**：`LootTable.getRandomItems` 与 `LootContextParamFiller` 会触碰 `ServerLevel` 关联的 `LegacyRandomSource`，后者不是线程安全的，后台线程与主线程 tick 并发会触发 `ThreadingDetector` 报错。因此**不用后台线程**，改为：

- 在服务端每 tick 末尾（`END_SERVER_TICK`）由 `tick(server)` 消费队列。
- 每 tick 最多处理 `MAX_TABLES_PER_TICK = 1` 个表（单表 10000 次约 1-3ms，不显著影响 tick 预算）。
- **双优先级队列**：`highQueue`（玩家解锁触发，插队）先于 `lowQueue`（启动批量填充）。
- `enqueued` 集合去重，避免同一表重复入队。
- 数据包重载期间 `pauseForReload()` / `resumeAfterReload()` 暂停消费。
- 队列排空后触发 `queueDrainedHandler`（批量广播目录哈希）。

### 7.3 模拟结果缓存

模拟结果通过 `LootProbabilityData`（SavedData，附加在 overworld）持久化。每张表存储其 JSON 内容哈希，重启时哈希未变则直接恢复缓存，避免重新模拟；数据包修改战利品表导致哈希变化时才重新模拟。详见 [考古笔记系统](journal.md) 的目录构建部分。

## 8. 战利品注入

[`ArchaeologyLootInjectors`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/injection/ArchaeologyLootInjectors.java) 是全局单例注入器，**平台差异化**：

| 平台 | 注入器 | 机制 |
|---|---|---|
| Fabric | `FabricArchaeologyLootInjector` | 模拟时显式调用 `maybeReplace`，向 drops 注入模组物品 |
| NeoForge | 空实现（默认） | 注入由 `IGlobalLootModifier`（GLM）在 `getRandomItems` 内部完成 |

> NeoForge 端不注册注入器，因为 GLM 已经在 `getRandomItems` 内部完成了注入，模拟器自然能抽取到注入的物品。Fabric 端没有 GLM，需要在模拟时显式调用注入器，确保模组物品被纳入概率统计与签名派生。

注入器接口 `ArchaeologyLootInjector` 是 `@FunctionalInterface`，签名为 `(tableId, drops, random) -> {}`。Fabric 端的注入实现见 [`fabric/.../loot/`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/loot/)（多个 `LootInjection` 类）。NeoForge 端的 GLM 见 [`neoforge/.../loot/`](../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/loot/)。

## 9. 自定义战利品条件

`condition/` 包定义模组自定义的战利品条件：

- [`ModLootConditions`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ModLootConditions.java) 注册 `mud_dredging` 条件类型，两端通过不同方式注册：
  - Fabric：`Registry.register` 直接注册（`onInitialize` 开头）。
  - NeoForge：`DeferredRegister` 注册（避免 registry frozen）。
- [`MudDredgingCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/MudDredgingCondition.java) 实现泥底打捞附魔的钓鱼条件判定（检查附魔、群系与概率）。

条件类型的注册时序约束见 [架构总览](architecture-overview.md) 的初始化流程--必须在 `UnsuspiciousBlockCommon.init()` 之前完成。

## 10. 扩展点

- **新增收录范围**：修改配置的追踪前缀列表（`ILootTableConfig.getArchaeologyPathPrefixes()`），或通过数据包新增命中前缀的战利品表。
- **自定义签名类型**：在 `LootResultSignature.SignatureType` 添加枚举，注意 `fromStoredKey` 的兼容性。签名类型变更会影响玩家存档，需配合 `NbtDataMigrator`。
- **新增战利品条件**：参考 `MudDredgingCondition`，在 `ModLootConditions` 注册类型，两端各自注册到注册表。
- **平台注入器**：Fabric 端如需新的注入逻辑，实现 `ArchaeologyLootInjector` 并在 `onInitialize` 调 `ArchaeologyLootInjectors.register`。
- **模拟调优**：`SIMULATION_COUNT`（精度 vs 性能）与 `MAX_TABLES_PER_TICK`（吞吐 vs tick 占用）是两个可调参数。

## 11. 相关文档

- [考古笔记系统](journal.md) - 目录构建、玩家进度、追踪的上层
- [配置与第三方联动](config-integrations.md) - 收录前缀等配置项
- [Mixin 总览](mixin.md) - `NestedLootTableMixin`（嵌套表捕获）
- [docs/journal-categories.md](../journal-categories.md) - 目录分类规则与领域语言
