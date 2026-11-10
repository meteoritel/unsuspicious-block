# 配置与第三方联动

本文档描述模组的配置系统、数据驱动与硬编码的边界，以及与第三方模组（Jade / JEI / Lootr / Trinkets / Curios / Artifacts / ModMenu）的联动实现。

> 本文件描述配置作用域、生命周期与联动入口；键名、范围和默认值以实际接口和平台声明为准。
> 可选依赖的反射加载机制以 [平台抽象](platform-spi.md) 为权威，本篇只列联动清单。

## 1. 职责概述

- **配置系统**：通过 `ILootTableConfig`、`ISpiritCatConfig` 与 `IPanningConfig` 配置能力通过 [平台 SPI](platform-spi.md) 注入，完整参数与约束直接查声明：

| 接口 | 职责 |
|---|---|
| [`ILootTableConfig`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ILootTableConfig.java) | 世界追踪范围、排除规则、签名排除组件、日志与追踪期限 |
| [`ISpiritCatConfig`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ISpiritCatConfig.java) | 灵体现世时长与九命效果持续时间 |
| [`IPanningConfig`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/IPanningConfig.java) | 淘洗生成、寿命、间距、结算和工具强度 |

淘洗工具标量由 `PanProfile` 通过 `DoubleSupplier` 延迟读取，避免在 NeoForge SERVER spec 尚未加载的物品构造期求值。世界生成稀有度由 placed feature 资源控制，详见[淘洗系统](../subsystems/panning.md)。

### 2.2 Fabric 实现

[`FabricLootTableConfig`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricLootTableConfig.java) **同时实现战利品与灵体配置接口**（`ILootTableConfig` + `ISpiritCatConfig`），用 GSON 读写 JSON：

- 战利品追踪配置：`<世界目录>/serverconfig/unsuspiciousblock.json`，服务端启动时加载，停止时解除世界绑定。
- 淘洗配置：[`FabricPanningConfig`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricPanningConfig.java) 实现第三接口，全局 JSON `config/unsuspiciousblock/panning.json`，字段缺失时自动补写默认值。
- 灵体猫全局配置：`config/unsuspiciousblock/unsuspiciousblock.json`。
- 世界配置首次创建时，以旧全局 JSON 中的三项战利品配置作为迁移初值，兼容已有玩家设置。
- 自动生成中英文 `README_CN.txt` / `README_EN.txt`（弥补 JSON 无注释的限制），已存在则保留玩家自定义备注。
- 钳制到合法范围（`clampLogEntries` / `clampTrackingTimeout` / `clampNpcLifetime` / `clampEffectDuration`），越界回退默认值，与 NeoForge 端 `defineInRange` 行为一致。
- 缺失灵体配置字段时自动补写修复。
- `save(rawPrefixes, rawExclusions, rawMaxLogEntries, rawTrackingTimeoutTicks)` 仅在集成服务器运行时可用，清洗（去空/去重）+ 钳制后写入当前世界配置。

### 2.3 NeoForge 实现

[`NeoForgeLootTableConfig`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgeLootTableConfig.java) 用 NeoForge 的 `ModConfigSpec`：

- `SERVER_CONFIG_SPEC` 注册为 `ModConfig.Type.SERVER`，保存三项战利品追踪配置并由 NeoForge 放入世界 `serverconfig`。
- 淘洗配置：[`NeoForgePanningConfig`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgePanningConfig.java) 实现第三接口，注册为独立的 SERVER ModConfigSpec。同一模组的同类配置默认文件名相同，因此必须显式指定文件名 `unsuspiciousblock-panning-server.toml`，否则 `ConfigTracker` 判定配置文件冲突并中断模组构造。
- `COMMON_CONFIG_SPEC` 保留灵体猫参数和旧版三项追踪值；旧值只作为新世界首次迁移初值，不再作为运行时权威配置。
- SERVER spec 使用一次性迁移标记，首次加载世界时复制旧 COMMON 值，之后不会覆盖该世界自己的设置。
- 用 `defineInRange` 声明范围约束，与 Fabric 端钳制行为对齐。
- 同样**同时实现战利品与灵体配置接口**。
- 玩家通过 NeoForge 的模组配置界面或服务端配置文件调整；多人游戏以服务器文件为权威。

### 2.4 ModMenu 配置界面

Fabric 端通过 [`ModMenuIntegration`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/client/modmenu/ModMenuIntegration.java) + [`ModMenuConfigScreen`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/client/modmenu/ModMenuConfigScreen.java) 提供分层图形化配置界面：

- **通用配置**：编辑灵体猫的六项全局参数，保存到 `config/unsuspiciousblock/unsuspiciousblock.json`，供本机托管的集成服务器使用。
- **服务端配置**：编辑当前世界的战利品追踪规则、日志上限与追踪超时。重置时同时清空管理页面产生的精确排除项。只有当前客户端正在运行集成服务器时允许写入；主菜单和多人客户端显示为服务端管理，避免本地修改造成误导。

### 2.5 运行时变更

[`ServerLootTableConfigManager`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/ServerLootTableConfigManager.java) 统一管理服务端配置生命周期：

- 服务端启动时在目录首次解析前加载当前世界配置，停止时清除世界绑定。
- 每秒比较一次配置快照。日志上限和追踪超时由业务代码实时读取，不需要重建。
- 追踪规则变化时暂停概率模拟、失效并重建目录，随后向在线玩家同步新目录哈希。
- 数据包重载（`/reload`、整合包换表）经平台重载监听器只置脏标记，重建在同一个 tick 路径上消费，与追踪规则变化走同一条重建路径；机制见 [战利品表机制细节](../internals/loottable-mechanics.md) 的「模拟结果缓存」一节。
- `/reload` 会重新读取 Fabric 世界 JSON；NeoForge SERVER spec 由平台负责加载。

### 2.6 战利品表名称文件

追踪规则属于按世界保存的 `ILootTableConfig`；补充语言按标准 JSON 保存到服务端全局 `config/unsuspiciousblock/loot_table_lang/<language>.json`，用于整合包分发和开发期补全。名称合并迁移、资源优先语义与运行时覆盖规则以 [战利品表机制细节](../internals/loottable-mechanics.md) 的「收录范围与自定义表名」一节为权威，此处只记录文件位置。

## 3. 数据驱动与硬编码边界

数据驱动三层分离原则：

### 3.1 数据驱动（可通过数据包修改）

- **猫猫信使礼物**：标准 Loot Table（`gameplay/cat/ghost_gift`）。
- **猫猫商人交易**：自定义 JSON（`data/merchant_cat_trades/`），支持输入/输出/次数/权重/条件。
- **猫之手结构**：结构 NBT + worldgen JSON + template_pool。
- **结构战利品**：标准 Loot Table。
- **Worldgen**：结构、structure_set、biome tag。
- **交易输入 Tag**：`unsuspiciousblock:random/*` 包装 Item Tag（随机陶片/唱片/盔甲纹饰模板），纳入 `c:` Conventional Tag，外部数据包扩展一个稳定入口即可。
- **考古笔记目录分类**：`data/journal_categories/`。
- **附魔定义**：`data/enchantment/*.json`（1.21 数据驱动附魔）。
- **配方**：`data/recipe/`。
- **战利品表**：`data/loot_table/`。

所有数据驱动内容支持 `/reload`。

### 3.2 可配置（服务端配置）

- 羁绊增减与冷却（`CatFavorAction` 的 favorDelta / cooldownTicks 目前硬编码，预留可调空间）。
- 坐卧时间和半径、恩惠数值。
- 灵体战斗与生命周期（`ISpiritCatConfig`）。
- 商人生成参数。
- 信使阶段时长。
- 战利品追踪前缀、日志上限、追踪超时（`ILootTableConfig`）。

### 3.3 硬编码（身份与关系语义，不可配置）

- 0-100 羁绊范围与六个羁绊阶段。
- 恩惠归属（能力与阶段的绑定）。
- 猫之手 UUID 绑定语义。
- 扣分优先级。
- 封顶跌落时清空命数（`catBond < 100 -> nineLivesCount = 0`）。
- 图腾与虚空规则（九命不与图腾叠加）。
- 剑士猫猫排除玩家（不攻击玩家）。
- 每名玩家同时一只信使和一只剑士的限制。

> 威慑与轻步是服务端保存的玩家偏好；猫之恩惠 HUD 没有客户端显示开关，始终按固定规则绘制。这避免服务端配置制造无法由 UI 和本地化正确解释的关系状态。

## 4. 第三方联动

所有联动均为**可选**，统一采用 `compileOnly` + `runtimeOnly`（开发运行环境）模式，发布时不强制安装。

### 4.1 Jade（方块与实体信息）

[`JadePlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/jade/JadePlugin.java)（common 的 platform 接入桥，通过 Fabric `jade` entrypoint 与 NeoForge `@WailaPlugin` 扫描发现）：

- 在 Jade 的方块信息中显示已扫描出的可疑方块战利品。
- 闪烁的光：通过实体服务端数据提供器按需同步剩余游戏刻，客户端显示距离自然消失的分钟与秒数（秒向上取整），世界生成点显示“不会自然消失”。未收到服务端数据时不显示寿命；显示随 Jade 数据刷新更新。
- `core.plugin.jade.JadeServerProviders` 注册服务端数据提供者，`client.plugin.jade.JadeClientProviders` 注册显示组件，共享字段由 `JadeData` 声明。
- `JadePlugin.register` 装配服务端 provider；[`JadeClientPlugin.registerClient`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/plugin/jade/JadeClientPlugin.java) 直接装配客户端 provider。Fabric 的 `jade` entrypoint 和 NeoForge 的 `@WailaPlugin` 扫描发现这两个 common 入口；插件构造不访问客户端实现，Jade 仅在物理客户端调用 `registerClient`。

### 4.2 JEI（配方查看）

[`JeiPlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/plugin/jei/JeiPlugin.java) + `PotteryWheelJeiCategory` + `PotteryWheelJeiRecipe`（common）：

- 注册陶轮配方的 JEI 类别。
- 注册 `JournalJeiGuiHandler`，将考古笔记中的已解锁物品条目暴露为 JEI clickable ingredient，支持 U/R 查看配方与用法；JEI 关闭后按其原生 Screen 返回链回到考古笔记。
- JEI runtime 向 `ArchaeologyJournalKeyHandler` 提供当前悬停原料，使玩家可在 JEI 物品上按手册快捷键按注册名搜索考古笔记。
- Fabric 端通过 `fabric.mod.json` 的 `jei_mod_plugin` entrypoint 注册；NeoForge 端无注册代码，由 JEI 自行扫描 `@JeiPlugin` 注解发现。
- JEI API 跨平台一致，故放在 common。陶轮自有背景、进度和物品/模型预览使用 kit，ingredient slot 和 focus link 继续由 JEI 持有。笔记联动直接读取根的唯一命中结果，覆盖面和捕获期间不暴露底层物品。

### 4.3 Lootr（每人独立战利品）

`common/core/plugin/lootr/`（可选，构建时可通过 `lootr_compat_*` 排除）：

- [`LootrArchaeologyFilterProvider`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/plugin/lootr/LootrArchaeologyFilterProvider.java)：实现 Lootr 的 `ILootrFilterProvider`，通过 `META-INF/services` 注册。
- `LootrBrushableTrackingAccess` / `LootrTrackingBridge`：桥接 Lootr 的每人独立战利品机制与模组的追踪系统。
- 配套 Lootr Mixin 由独立配置登记，接线见 [Mixin](mixin.md)。
- 当前发布配置：NeoForge 带 Lootr 兼容，Fabric 不带。

### 4.4 Trinkets / Curios（饰品栏）

标本箱可装备到饰品栏，并代理箱内兼容饰品的属性与效果：

| 平台 | 集成类 | 职责 |
|---|---|---|
| Fabric | [`FabricTrinketsIntegration`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/trinket/FabricTrinketsIntegration.java) | 入口，通过 `OptionalModIntegration` 反射加载 |
| | `SpecimenBoxTrinket` | 标本箱作为 Trinkets 饰品 |
| | `TrinketSlotResolver` | 饰品槽解析 |
| | `TrinketsAccessoryHelper` | 实现 `IAccessoryHelper`（查询饰品栏） |
| NeoForge | [`NeoForgeCuriosIntegration`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/curio/NeoForgeCuriosIntegration.java) | 入口 |
| | `SpecimenBoxCurio` | 标本箱作为 Curios 饰品 |
| | `CuriosAccessoryHelper` | 实现 `IAccessoryHelper` |

两端 `IAccessoryHelper` 实现在饰品模组未安装时返回 false / 空流，玩法降级为"未装备饰品"。

### 4.5 Artifacts（饰品效果代理）

`fabric/platform/plugin/artifacts/` 与 `neoforge/platform/plugin/artifacts/` 各有 `ArtifactsSpecimenBoxSlotProvider`：

- 标本箱代理箱内 Artifacts 兼容饰品的装备效果。
- 仅编译其装备 provider API，运行时保持可选联动。

### 4.6 ModMenu（配置界面入口）

`fabric/client/modmenu/`（仅 Fabric）：

- `ModMenuIntegration`：注册 ModMenu 入口。
- `ModMenuConfigScreen`：区分通用配置与当前世界服务端配置的入口页面。
- `SpiritCatConfigScreen`：编辑灵体猫全局配置，通过 `FabricLootTableConfig.saveSpiritCatConfig` 落盘。
- `ServerLootConfigScreen`：编辑当前世界的战利品服务端配置，通过 `FabricLootTableConfig.save` 落盘。

### 4.7 配置页面

Fabric ModMenu 首页及配置页、NeoForge Mods 配置工厂均使用独立 Meteo’s UI Kit。`ConfigFormScreen` 只负责配置页的滚动排版、稳定控件与固定操作区；平台页面保留字段、校验和保存所有权。重置只改草稿，取消不保存。

NeoForge 页面从本模组已注册的 `ModConfigSpec` 派生配置文件入口与字段，包含独立淘洗 SERVER 配置。字段采用同一 spec 的类型、默认值和校验器，全部验证后写入并调用 `save()`。未加载的 spec、远程服务器 SERVER 配置与已开放到 LAN 的集成服务器 SERVER 配置不可编辑。当前注册字段均无重启标记。


## 5. 扩展点：新增配置项 / 数据驱动内容 / 联动

- **新增可配置参数**：在 `ILootTableConfig` 或 `ISpiritCatConfig` 加方法与默认值常量，两端配置类各自实现并读取。
- **新增数据驱动内容**：在 `data/unsuspiciousblock/` 对应目录添加 JSON（战利品表、配方、商人交易、目录分类等）。
- **新增第三方联动**：
  1. `compileOnly` 引用第三方 API（两端 build.gradle）。
  2. 按职责在 core/plugin、client/plugin 或 Loader platform/plugin 放置实现；需要惰性加载时使用 `OptionalModIntegration`，机制见[平台抽象](platform-spi.md)。
  3. 跨平台 API 放 common，平台专属 API 放 fabric/neoforge。
- **调整 Lootr 兼容**：通过 `gradle.properties` 的 `lootr_compat_*` 属性控制构建。

## 6. 相关文档

- [平台抽象](platform-spi.md) - SPI 接口与 `OptionalModIntegration`
- [猫族关系系统](../subsystems/cat-favor.md) - `ISpiritCatConfig` 的使用
- [战利品表系统](../subsystems/loottable.md) - `ILootTableConfig` 的追踪前缀
- [考古笔记系统](../subsystems/journal.md) - 日志上限与追踪超时
- [方块与物品](../subsystems/blocks-items.md) - 标本箱饰品代理
- [架构总览](architecture.md) - 构建与可选依赖
