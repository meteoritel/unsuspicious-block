# 配置与第三方联动

本文档描述模组的配置系统、数据驱动与硬编码的边界，以及与第三方模组（Jade / JEI / Lootr / Trinkets / Curios / Artifacts / ModMenu）的联动实现。

## 1. 职责概述

- **配置系统**：通过 `ILootTableConfig` 与 `ISpiritCatConfig` 两个 SPI 接口暴露可调参数。战利品追踪配置由服务端按世界持有；Fabric 使用世界目录 JSON，NeoForge 使用 SERVER ModConfigSpec。灵体猫参数仍为全局配置。
- **数据驱动边界**：可枚举内容交数据包，可调强度交配置，身份/关系语义保持硬编码（见 [ADR 0007](../adr/0007-cat-system-separates-content-balance-and-domain-rules.md)）。
- **第三方联动**：六类可选联动，统一用 `compileOnly` + `OptionalModIntegration` 反射加载，发布时不强制依赖。

## 2. 配置系统

### 2.1 配置接口

两个 SPI 接口（见 [平台抽象](platform-abstraction.md)）定义可调参数与领域约束常量：

[`ILootTableConfig`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ILootTableConfig.java)（战利品表与日志）：

| 参数 | 范围 | 默认 | 说明 |
|---|---|---|---|
| `archaeology_path_prefixes` | - | 见下 | 战利品表追踪前缀列表 |
| `excluded_loot_tables` | - | 空 | 从宽泛规则中精确排除的战利品表 ID |
| `max_log_entries_per_table` | 64-4096 | 512 | 服务器全局单表日志兜底上限；玩家当前表自动保留上限不能超过此值 |
| `tracking_timeout_ticks` | 600-60000 | 6000（5 分钟） | 战利品箱追踪超时 |

默认追踪前缀：

```
archaeology/, archeology/, pots/,
minecraft:gameplay/fishing,
minecraft:chests/buried_treasure,
minecraft:chests/ancient_city, minecraft:chests/ancient_city_ice_box,
unsuspiciousblock:gameplay/fishing/,
unsuspiciousblock:gameplay/fossil_hunter/
```

[`ISpiritCatConfig`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ISpiritCatConfig.java)（猫国灵体）：

| 参数 | 范围 | 默认 | 说明 |
|---|---|---|---|
| `messenger_lifetime_ticks` | 20-1728000 | 600 | 信使最长现世时间 |
| `swordsman_lifetime_ticks` | 20-1728000 | 600 | 剑士最长现世时间 |
| `merchant_lifetime_ticks` | 20-1728000 | 48000 | 商人最长现世时间 |
| `invulnerability_duration_ticks` | 1-72000 | 40 | 九命纯无敌时间 |
| `resistance_duration_ticks` | 1-72000 | 600 | 九命抗性提升 II 时间 |
| `fire_resistance_duration_ticks` | 1-72000 | 600 | 九命防火 I 时间 |

### 2.2 Fabric 实现

[`FabricLootTableConfig`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricLootTableConfig.java) **同时实现两个接口**（`ILootTableConfig` + `ISpiritCatConfig`），用 GSON 读写 JSON：

- 战利品追踪配置：`<世界目录>/serverconfig/unsuspiciousblock.json`，服务端启动时加载，停止时解除世界绑定。
- 灵体猫全局配置：`config/unsuspiciousblock/unsuspiciousblock.json`。
- 世界配置首次创建时，以旧全局 JSON 中的三项战利品配置作为迁移初值，兼容已有玩家设置。
- 自动生成中英文 `README_CN.txt` / `README_EN.txt`（弥补 JSON 无注释的限制），已存在则保留玩家自定义备注。
- 钳制到合法范围（`clampLogEntries` / `clampTrackingTimeout` / `clampNpcLifetime` / `clampEffectDuration`），越界回退默认值，与 NeoForge 端 `defineInRange` 行为一致。
- 缺失灵体配置字段时自动补写修复。
- `save(rawPrefixes, rawMaxLogEntries, rawTrackingTimeoutTicks)` 仅在集成服务器运行时可用，清洗（去空/去重）+ 钳制后写入当前世界配置。

### 2.3 NeoForge 实现

[`NeoForgeLootTableConfig`](../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgeLootTableConfig.java) 用 NeoForge 的 `ModConfigSpec`：

- `SERVER_CONFIG_SPEC` 注册为 `ModConfig.Type.SERVER`，保存三项战利品追踪配置并由 NeoForge 放入世界 `serverconfig`。
- `COMMON_CONFIG_SPEC` 保留灵体猫参数和旧版三项追踪值；旧值只作为新世界首次迁移初值，不再作为运行时权威配置。
- SERVER spec 使用一次性迁移标记，首次加载世界时复制旧 COMMON 值，之后不会覆盖该世界自己的设置。
- 用 `defineInRange` 声明范围约束，与 Fabric 端钳制行为对齐。
- 同样**同时实现两个接口**。
- 玩家通过 NeoForge 的模组配置界面或服务端配置文件调整；多人游戏以服务器文件为权威。

### 2.4 ModMenu 配置界面

Fabric 端通过 [`ModMenuIntegration`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/modmenu/ModMenuIntegration.java) + [`ModMenuConfigScreen`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/modmenu/ModMenuConfigScreen.java) 提供分层图形化配置界面：

- **通用配置**：编辑灵体猫的六项全局参数，保存到 `config/unsuspiciousblock/unsuspiciousblock.json`，供本机托管的集成服务器使用。
- **服务端配置**：编辑当前世界的战利品追踪规则、日志上限与追踪超时。重置时同时清空管理页面产生的精确排除项。只有当前客户端正在运行集成服务器时允许写入；主菜单和多人客户端显示为服务端管理，避免本地修改造成误导。

### 2.5 运行时变更

[`ServerLootTableConfigManager`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/ServerLootTableConfigManager.java) 统一管理服务端配置生命周期：

- 服务端启动时在目录首次解析前加载当前世界配置，停止时清除世界绑定。
- 每秒比较一次配置快照。日志上限和追踪超时由业务代码实时读取，不需要重建。
- 追踪规则变化时暂停概率模拟、失效并重建目录，随后向在线玩家同步新目录哈希。
- `/reload` 会重新读取 Fabric 世界 JSON；NeoForge SERVER spec 由平台负责加载。

### 2.6 战利品表名称文件

追踪配置和名称均由服务器管理，但分开存储：追踪规则属于 `ILootTableConfig`；多语言名称保存到当前世界 `serverconfig/unsuspiciousblock-loot-table-names.json`。服务端向所有客户端派发名称，客户端合并保存到全局 `config/unsuspiciousblock/lang/<language>.json`，因此该客户端的所有存档共享这些名称；同一自动 key 在不同服务器取值冲突时，以最近收到的服务端值为准。

客户端文件采用标准语言 JSON 格式，并承担原有缺失 key 自动导出功能。运行时语言覆盖仅接受 `LootTableNames` 自动生成的 key，不允许借配置覆盖模组其他 GUI 文本。

## 3. 数据驱动与硬编码边界

遵循 [ADR 0007](../adr/0007-cat-system-separates-content-balance-and-domain-rules.md) 的三层分离：

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

- 羁绊增减与冷却（`CatFavorAction` 的 favorDelta / cooldownTicks 目前硬编码，但 ADR 0007 提及可调）。
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

> 客户端只提供 HUD 显示开关；威慑与轻步是服务端保存的玩家偏好。这避免服务端配置制造无法由 UI 和本地化正确解释的关系状态。

## 4. 第三方联动

所有联动均为**可选**，统一采用 `compileOnly` + `runtimeOnly`（开发运行环境）模式，发布时不强制安装。

### 4.1 Jade（方块信息）

[`JadePlugin`](../../common/src/main/java/com/meteorite/unsuspiciousblock/plugin/jade/JadePlugin.java)（common，通过 `fabric.mod.json` 的 `jade` entrypoint 与 NeoForge 事件注册）：

- 在 Jade 的方块信息中显示已扫描出的可疑方块战利品。
- Jade API 跨平台一致，故放在 common。

### 4.2 JEI（配方查看）

[`JeiPlugin`](../../common/src/main/java/com/meteorite/unsuspiciousblock/plugin/jei/JeiPlugin.java) + `PotteryWheelJeiCategory` + `PotteryWheelJeiRecipe`（common）：

- 注册陶轮配方的 JEI 类别。
- 通过 `fabric.mod.json` 的 `jei_mod_plugin` entrypoint 与 NeoForge 事件注册。
- JEI API 跨平台一致，故放在 common。

### 4.3 Lootr（每人独立战利品）

`common/plugin/lootr/`（可选，构建时可通过 `lootr_compat_*` 排除）：

- [`LootrArchaeologyFilterProvider`](../../common/src/main/java/com/meteorite/unsuspiciousblock/plugin/lootr/LootrArchaeologyFilterProvider.java)：实现 Lootr 的 `ILootrFilterProvider`，通过 `META-INF/services` 注册。
- `LootrBrushableTrackingAccess` / `LootrTrackingBridge`：桥接 Lootr 的每人独立战利品机制与模组的追踪系统。
- 配套 5 个 mixin（见 [mixin.md](mixin.md) 第 5 节）。
- 当前发布配置：NeoForge 带 Lootr 兼容，Fabric 不带。

### 4.4 Trinkets / Curios（饰品栏）

标本箱可装备到饰品栏，并代理箱内兼容饰品的属性与效果：

| 平台 | 集成类 | 职责 |
|---|---|---|
| Fabric | [`FabricTrinketsIntegration`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/plugin/trinket/FabricTrinketsIntegration.java) | 入口，通过 `OptionalModIntegration` 反射加载 |
| | `SpecimenBoxTrinket` | 标本箱作为 Trinkets 饰品 |
| | `TrinketSlotResolver` | 饰品槽解析 |
| | `TrinketsAccessoryHelper` | 实现 `IAccessoryHelper`（查询饰品栏） |
| NeoForge | [`NeoForgeCuriosIntegration`](../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/plugin/curio/NeoForgeCuriosIntegration.java) | 入口 |
| | `SpecimenBoxCurio` | 标本箱作为 Curios 饰品 |
| | `CuriosAccessoryHelper` | 实现 `IAccessoryHelper` |

两端 `IAccessoryHelper` 实现在饰品模组未安装时返回 false / 空流，玩法降级为"未装备饰品"。

### 4.5 Artifacts（饰品效果代理）

`fabric/plugin/artifacts/` 与 `neoforge/plugin/artifacts/` 各有 `ArtifactsSpecimenBoxSlotProvider`：

- 标本箱代理箱内 Artifacts 兼容饰品的装备效果。
- 仅编译其装备 provider API，运行时保持可选联动。

### 4.6 ModMenu（配置界面入口）

`fabric/modmenu/`（仅 Fabric）：

- `ModMenuIntegration`：注册 ModMenu 入口。
- `ModMenuConfigScreen`：区分通用配置与当前世界服务端配置的入口页面。
- `SpiritCatConfigScreen`：编辑灵体猫全局配置，通过 `FabricLootTableConfig.saveSpiritCatConfig` 落盘。
- `ServerLootConfigScreen`：编辑当前世界的战利品服务端配置，通过 `FabricLootTableConfig.save` 落盘。

## 5. 可选依赖加载机制

跨可选依赖边界的调用统一通过 [`OptionalModIntegration`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/OptionalModIntegration.java) 反射完成（见 [平台抽象](platform-abstraction.md) 第 4 节）：

```java
if (Services.PLATFORM.isModLoaded("trinkets")) {
    OptionalModIntegration.instantiate(
            "com.meteorite.unsuspiciousblock.plugin.trinket.FabricTrinketsIntegration",
            Runnable.class).run();
}
```

集成类在 `run()` 中才真正引用第三方 API，配合 `isModLoaded` 前置判断，保证依赖缺失时不会类加载失败。NeoForge 端 Curios 同此模式。

## 6. 扩展点

- **新增可配置参数**：在 `ILootTableConfig` 或 `ISpiritCatConfig` 加方法与默认值常量，两端配置类各自实现并读取。
- **新增数据驱动内容**：在 `data/unsuspiciousblock/` 对应目录添加 JSON（战利品表、配方、商人交易、目录分类等）。
- **新增第三方联动**：
  1. `compileOnly` 引用第三方 API（两端 build.gradle）。
  2. 在 `plugin/` 下写集成类，通过 `OptionalModIntegration` 反射加载。
  3. 跨平台 API 放 common，平台专属 API 放 fabric/neoforge。
- **调整 Lootr 兼容**：通过 `gradle.properties` 的 `lootr_compat_*` 属性控制构建。

## 7. 相关文档

- [平台抽象](platform-abstraction.md) - SPI 接口与 `OptionalModIntegration`
- [猫族关系系统](cat-favor.md) - `ISpiritCatConfig` 的使用
- [战利品表系统](loottable.md) - `ILootTableConfig` 的追踪前缀
- [考古笔记系统](journal.md) - 日志上限与追踪超时
- [方块与物品](blocks-items.md) - 标本箱饰品代理
- [架构总览](architecture-overview.md) - 构建与可选依赖
- [docs/adr/0007](../adr/0007-cat-system-separates-content-balance-and-domain-rules.md) - 三层分离决策
