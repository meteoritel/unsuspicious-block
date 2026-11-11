# 配置与第三方联动

本文档说明模组的配置架构、双端存储与配置界面、数据驱动与硬编码边界，以及与第三方模组的联动接入方式。

> 配置键名、默认数值与合法上下限以各 SPI 接口与平台声明为唯一事实源；可选联动的惰性反射机制见[平台抽象](platform-spi.md)。

## 1. 配置接口概览

模组通过三个配置 SPI 接口将可调参数注入 `common`：

| SPI 接口 | 覆盖范围 | 说明 |
|---|---|---|
| [`ILootTableConfig`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ILootTableConfig.java) | 世界追踪范围规则、精确排除项、实例态排除组件、日志上限与容器追踪超时 | 按世界保存，服务端权威 |
| [`ISpiritCatConfig`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ISpiritCatConfig.java) | 灵体猫现世时长与猫之九命触发后的保护效果时长 | 全局配置 |
| [`IPanningConfig`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/IPanningConfig.java) | 淘洗点自然生成节拍、寿命、间距、采空冷却与淘盘数值加成 | 独立配置文件 / Spec |

> **物品构造期的延迟求值约束**：`PanProfile` 读取 `gold_pan_luck_bonus` 等工具标量时必须使用 `DoubleSupplier` 延迟求值，因为 NeoForge 在注册物品期间尚未加载 `SERVER` 类型的 `ModConfigSpec`。

## 2. 双端配置实现与生命周期

### 2.1 Fabric 实现

- [`FabricLootTableConfig`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricLootTableConfig.java) 同时实现 `ILootTableConfig` 与 `ISpiritCatConfig`，使用 GSON 读写 JSON：
  - **世界战利品追踪配置**：保存在 `<世界目录>/serverconfig/unsuspiciousblock.json`，服务端启动时加载，停止时解除世界绑定；首次为世界创建配置时，会从旧全局 JSON 迁移三项战利品设置。
  - **灵体猫全局配置**：保存在 `config/unsuspiciousblock/unsuspiciousblock.json`，缺失字段自动补写默认值。
  - 自动在配置旁生成 `README_CN.txt` / `README_EN.txt`（若已存在则保留用户修改）。
- [`FabricPanningConfig`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricPanningConfig.java) 实现 `IPanningConfig`，读写全局文件 `config/unsuspiciousblock/panning.json`，缺失字段自动补齐。
- 所有数值读取与写盘均经过范围钳制（越界回退默认值）；`save(...)` 仅在集成服务器运行期间允许写入当前世界配置，并在落盘前完成去重清洗。

### 2.2 NeoForge 实现

- [`NeoForgeLootTableConfig`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgeLootTableConfig.java) 基于 `ModConfigSpec` 同时实现 `ILootTableConfig` 与 `ISpiritCatConfig`：
  - `SERVER_CONFIG_SPEC`（`ModConfig.Type.SERVER`）：由 NeoForge 放入世界目录的 `serverconfig/` 下；首次加载新世界时通过一次性迁移标记复制旧 `COMMON` 配置中的追踪值，此后世界配置独立演进。
  - `COMMON_CONFIG_SPEC`：保存灵体猫全局参数及仅用于初次迁移的旧追踪字段。
- [`NeoForgePanningConfig`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgePanningConfig.java) 实现 `IPanningConfig`，注册为独立的 `SERVER` 规格并显式指定文件名 `unsuspiciousblock-panning-server.toml`，避免与同模组同类型默认配置文件名冲突导致 `ConfigTracker` 中断启动。
- 所有范围校验由 `defineInRange` 声明，与 Fabric 端钳制规则保持一致；多人游戏以服务端配置为权威。

### 2.3 配置界面与可写性规则

两端配置页面均使用 **Meteo's UI Kit** 的通用表单容器 `ConfigFormScreen` 负责滚动排版、输入控件与固定底部操作栏；字段定义、校验规则与保存逻辑仍由各自平台页面持有。重置操作仅修改草稿状态，点击取消不落盘。

- **Fabric（ModMenu 入口）**：[`ModMenuIntegration`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/client/modmenu/ModMenuIntegration.java) 与 [`ModMenuConfigScreen`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/client/modmenu/ModMenuConfigScreen.java) 提供入口，划分为两类子页：
  - `SpiritCatConfigScreen`：编辑灵体猫全局参数并保存到 `config/unsuspiciousblock/unsuspiciousblock.json`。
  - `ServerLootConfigScreen`：编辑当前世界的战利品追踪规则（重置时同步清空管理页产生的精确排除项）。仅在本机运行集成服务器时允许修改与保存；主菜单或多人游戏客户端仅显示提示，防止本地修改误导玩家。
- **NeoForge（Mods 配置工厂）**：从本模组已注册的 `ModConfigSpec` 自动派生文件入口与字段（含独立的淘洗 `SERVER` 配置），复用同一组默认值与校验器，全部通过校验后写回并调用 `save()`。未加载的规格、远程多人服务器配置以及已开放局域网联机的集成服务器 `SERVER` 配置均为只读。当前所有配置项均不需要重启游戏。

### 2.4 运行时热重载与补充表名文件

[`ServerLootTableConfigManager`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/ServerLootTableConfigManager.java) 统一管理服务端配置生命周期：

- 服务端启动时先于目录解析加载当前世界配置，停服时清除绑定。
- 每秒比对一次配置快照：
  - 日志保留上限与追踪超时由业务代码实时读取，更改后立即生效，无需重建目录；
  - 追踪前缀或排除规则变化时，暂停概率模拟并失效重建目录，完成后向在线玩家广播新的目录哈希。
- 数据包重载（`/reload`）时，平台监听仅标记脏状态，由管理器在服务端 tick 路径上与配置变更走同一条重建链路；Fabric 端在 `/reload` 时同步重读世界 JSON，NeoForge 端由平台自动重载 `SERVER` 规格。
- **补充表名语言文件**：服务端将自定义战利品表名称保存在全局文件 `config/unsuspiciousblock/loot_table_lang/<language>.json`（与按世界保存的 `ILootTableConfig` 分离）。合并迁移、资源优先语义与运行时覆盖规则见[战利品表系统](../subsystems/loottable.md)与[战利品表机制细节](../internals/loottable-mechanics.md)。

## 3. 数据驱动与硬编码边界

### 3.1 数据驱动内容（支持 `/reload`）

| 内容 | 数据包位置 / 格式 | 说明 |
|---|---|---|
| 猫猫信使礼物 | `data/unsuspiciousblock/loot_table/gameplay/cat/ghost_gift.json` | 标准战利品表 |
| 猫猫商人交易 | `data/unsuspiciousblock/merchant_cat_trades/*.json` | 支持输入/输出、次数、权重与条件 |
| 猫之手结构与世界生成 | `data/unsuspiciousblock/structure/` 与 `worldgen/` | 结构模板与群系范围（自然生成当前停用） |
| 交易随机商品包装 Tag | `data/unsuspiciousblock/tags/item/random/*.json` | 聚合 `c:` 通用 Tag，供外部数据包统一切入扩展 |
| 考古笔记目录分类 | `data/<namespace>/journal_categories/*.json` | 支持任意命名空间扩展，见[笔记分类格式](../../api/journal-categories.md) |
| 附魔、配方、战利品表与地物 | `data/unsuspiciousblock/{enchantment,recipe,loot_table,worldgen}/` | 标准 1.21.1 数据驱动资源 |

### 3.2 服务端配置与代码核心常量边界

- **服务端配置暴露**：战利品追踪前缀/排除规则、日志上限与追踪超时（`ILootTableConfig`）；灵体猫现世时长与九命效果持续时间（`ISpiritCatConfig`）；淘洗生成频率、寿命、冷却与工具数值加成（`IPanningConfig`）。
- **保持硬编码的核心契约**：
  - 猫族关系的基础不变量（0–100 范围、6 个阶段边界与恩惠归属、UUID 绑定替代、扣分去重、跌破 100 清空命数、九命与图腾/虚空规则、灵体单玩家数量限制与剑士排除玩家）；目前行为增减与冷却也在 `CatFavorAction` 中以代码常量维护；
  - 玩家状态偏好（猫之威慑与轻步开关）保存于服务端玩家存档；猫之恩惠 HUD 始终按携带与阶段规则自动绘制，不提供客户端隐藏开关。
  - 玩法规则细节以[猫族关系规格](../../design/cat-bond.md)与[猫国灵体规格](../../design/spirit-cat-npc.md)为准。

## 4. 第三方联动清单

所有联动均为可选依赖，使用 `compileOnly` + 开发期 `runtimeOnly` 引入，缺失时平滑降级。

### 4.1 Jade（方块与实体信息）

- **发现入口**：[`JadePlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/jade/JadePlugin.java) 与 [`JadeClientPlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/plugin/jade/JadeClientPlugin.java) 位于 `common`，由 Fabric `jade` entrypoint 与 NeoForge `@WailaPlugin` 扫描装配。插件构造期不接触客户端类，客户端 Provider 仅在物理客户端调用 `registerClient` 时注册。
- **服务端/客户端分离**：`core.plugin.jade.JadeServerProviders` 注册服务端数据提供器，`client.plugin.jade.JadeClientProviders` 注册显示组件，共享字段声明在 `JadeData`。
- **提供信息**：显示已扫描的可疑方块战利品；针对淘洗点（如闪烁的光），按需同步剩余游戏刻并在客户端显示“X 分 Y 秒后消散”或“不会自然消失”（无服务端数据时不显示寿命）。

### 4.2 JEI（配方与物品查询）

- **发现入口**：[`JeiPlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/plugin/jei/JeiPlugin.java) 位于 `common/client/`（Fabric 由 `fabric.mod.json` 声明，NeoForge 由 `@JeiPlugin` 注解自动发现）。
- **集成能力**：
  - `PotteryWheelJeiCategory` / `PotteryWheelJeiRecipe`：注册纹饰陶轮台配方类别（背景、进度与模型预览复用 UI Kit 绘制，原料槽位与焦点链接交由 JEI 管理）。
  - `JournalJeiGuiHandler`：将考古笔记网格与介绍页中的已解锁物品暴露为 JEI 可点击原料，支持 `U` / `R` 键跳转并沿 Screen 返回链回到笔记；浮层或输入捕获激活时不暴露底层物品。
  - JEI 运行时悬停原料查询配合 `ArchaeologyJournalKeyHandler`，支持在 JEI 列表上按笔记快捷键按注册名搜索。

### 4.3 Lootr（每人独立战利品）

- 实现位于 `common/core/plugin/lootr/`（受 `gradle.properties` 的 `lootr_compat_*` 构建开关控制；当前发布配置仅 NeoForge 启用）：
  - [`LootrArchaeologyFilterProvider`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/plugin/lootr/LootrArchaeologyFilterProvider.java) 通过 `META-INF/services` 注册；
  - `LootrBrushableTrackingAccess` 与 `LootrTrackingBridge` 负责把 Lootr 的每人独立容器/可疑方块接入模组追踪体系；配套 Mixin 见[Mixin 边界与接线](mixin.md)。

### 4.4 Trinkets / Curios（饰品栏）

使标本箱可装备到饰品栏并参与 `CarriedItems` 的便携携带查询：

| 平台 | 集成入口与组件 | 职责 |
|---|---|---|
| Fabric | [`FabricTrinketsIntegration`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/trinket/FabricTrinketsIntegration.java) | 由 `OptionalModIntegration` 反射加载；含 `SpecimenBoxTrinket`、`TrinketSlotResolver` 与 `TrinketsAccessoryHelper`（实现 `IAccessoryHelper`） |
| NeoForge | [`NeoForgeCuriosIntegration`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/curio/NeoForgeCuriosIntegration.java) | 入口及 `SpecimenBoxCurio`、`CuriosAccessoryHelper`（实现 `IAccessoryHelper`） |

当饰品模组未安装时，`IAccessoryHelper` 默认回退实现返回空流，玩法等价于“未装备饰品”。

### 4.5 Artifacts（饰品效果代理）

`fabric/platform/plugin/artifacts/` 与 `neoforge/platform/plugin/artifacts/` 各自实现 `ArtifactsSpecimenBoxSlotProvider`，使标本箱放在背包或饰品栏时能代理箱内兼容 Artifacts 饰品的装备效果。

## 5. 扩展点

- **新增配置参数**：在 `ILootTableConfig`、`ISpiritCatConfig` 或 `IPanningConfig` 中增加方法与默认值/边界常量，并在两端平台配置类与配置页面同步实现。
- **新增数据驱动条目**：在 `common/src/main/resources/data/unsuspiciousblock/` 对应子目录添加 JSON 定义。
- **新增第三方联动**：
  1. 在 `fabric/build.gradle` 与 `neoforge/build.gradle` 添加 `compileOnly`（及按需 `runtimeOnly`）；
  2. 跨平台 API 置于 `common` 的 `core/plugin`、`client/plugin` 或 `platform/plugin`；平台专属 API 置于对应 Loader 的 `platform/plugin/`，需要惰性加载时使用 `OptionalModIntegration`（见[平台抽象](platform-spi.md)）。
- **调整 Lootr 构建范围**：修改 `gradle.properties` 中的 `lootr_compat_fabric` / `lootr_compat_neoforge` 属性。

## 6. 相关文档

- [平台抽象](platform-spi.md)
- [架构总览](architecture.md)
- [Mixin 边界与接线](mixin.md)
- [战利品表系统](../subsystems/loottable.md)
- [考古笔记系统](../subsystems/journal.md)
- [猫族关系系统](../subsystems/cat-favor.md)
- [淘洗系统](../subsystems/panning.md)
- [方块与物品](../subsystems/blocks-items.md)
