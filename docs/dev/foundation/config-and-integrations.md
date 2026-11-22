# 配置与第三方联动

本文档说明模组的配置架构、双端存储与配置界面、数据驱动与硬编码边界，以及与第三方模组的联动接入方式。

## 1. 配置归属

| 配置 | 代码入口 | 作用域 |
| --- | --- | --- |
| 战利品追踪与考古日志参数 | [`LootTrackingSettings`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/LootTrackingSettings.java)、[`ServerLootTableConfigManager`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/ServerLootTableConfigManager.java) | 双端共用世界文件，服务端权威 |
| 灵体猫现世时长与九命保护 | [`FabricSpiritCatConfig`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricSpiritCatConfig.java)、[`NeoForgeSpiritCatConfig`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgeSpiritCatConfig.java) | Fabric 全局 JSON，NeoForge COMMON spec |
| 淘洗点生成和淘盘数值 | [`FabricPanningConfig`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/FabricPanningConfig.java)、[`NeoForgePanningConfig`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/NeoForgePanningConfig.java) | Fabric 全局 `config/unsuspiciousblock/panning.json`，NeoForge 独立 `unsuspiciousblock-panning-server.toml` SERVER spec |

`PanProfile` 在读取服务端工具参数时使用延迟求值，因为 NeoForge 在注册物品时尚未加载 SERVER spec。

## 2. 世界战利品追踪配置

Fabric 与 NeoForge 共用 `<世界目录>/serverconfig/unsuspiciousblock-loot-tracking.json`。服务端启动时加载此文件，然后构建考古笔记目录。字段格式由 [`LootTrackingConfigFile`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/LootTrackingConfigFile.java) 定义：

| 字段 | 生效规则 |
| --- | --- |
| `schema_version` | 世界配置格式版本，当前为 `1` |
| `follow_builtin_rules` | 是否使用当前模组内置规则，默认 `true` |
| `disabled_builtin_rules` | 禁用的内置规则标识 |
| `additional_tracking_rules` | 世界附加选择器，语法见[战利品表系统](../subsystems/loottable.md#5-收录范围与自定义表名) |
| `excluded_loot_tables` | 使用完整 `namespace:path` 的根表排除项 |
| `signature_excluded_components`、`max_log_entries_per_table`、`tracking_timeout_ticks` | 可选覆盖。省略字段继承默认值，显式空组件列表关闭签名排除 |

### 2.1 旧配置导入

首次装载该世界且缺少共用文件时，平台适配器按优先级读取旧配置：

- Fabric 先查世界旧 `serverconfig/unsuspiciousblock.json`，其次查实例 `config/unsuspiciousblock/unsuspiciousblock.json` 的旧追踪规则和日志参数。
- NeoForge 依次查世界 `serverconfig/`、实例 `config/` 和 FML 默认配置目录中的 `unsuspiciousblock-server.toml`。合法显式 SERVER 字段优先。未从 COMMON 复制的旧文件可用旧 COMMON 补齐缺字段，已复制的旧文件对缺字段采用旧默认值。

与 `v1.5.2` 规则等价的条目跟随内置规则，缺少的旧默认项保留为禁用，自定义项保留为附加规则。显式空规则列表继续关闭内置规则。迁移和编辑原子写入世界文件。原有迁移输入仍保留；配置读取失败时可查看现有规则，服务端日志记录出错路径。修复文件后执行 `/reload` 即可重读。

### 2.2 配置界面与热重载

Fabric ModMenu、NeoForge Mods 和考古笔记追踪管理均可打开共用的 [`LootTrackingConfigScreen`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/screen/LootTrackingConfigScreen.java)。页面使用 `ConfigFormScreen` 展示草稿，通过服务端预览收录影响后提交。加载当前版本时可保留已编辑的字段，退出时可确认舍弃草稿。专用服务器及 LAN 需要权限等级 2，私有单人的世界主人可以编辑世界规则与实例级补充表名。旧 NeoForge COMMON 追踪字段只作为迁移输入，通用配置表单显示当前可编辑字段。

预览和提交校验配置版本与资源代次。磁盘原子写入成功后给出已保存版本，候选目录构建成功后同步发布目录、规则与签名策略。构建失败可通过 `/reload` 或目录重建命令恢复，管理页保留已保存和已生效版本。仅变更日志上限或追踪超时时可复用已发布目录。两端可直接编辑共用 JSON 后执行 `/reload`。补充表名的作用域和合并规则见[战利品表系统](../subsystems/loottable.md)。

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

- **服务端配置暴露**：战利品追踪前缀/排除规则、日志上限与追踪超时（`LootTrackingSettings`）；灵体猫现世时长与九命效果持续时间（`ISpiritCatConfig`）；淘洗生成频率、寿命、冷却与工具数值加成（`IPanningConfig`）。
- **保持硬编码的核心契约**：
  - 猫族关系的基础不变量（0–100 范围、6 个阶段边界与恩惠归属、UUID 绑定替代、扣分去重、跌破 100 清空命数、九命与图腾/虚空规则、灵体单玩家数量限制与剑士排除玩家）；目前行为增减与冷却也在 `CatFavorAction` 中以代码常量维护；
  - 玩家状态偏好（猫之威慑与轻步开关）保存于服务端玩家存档；猫之恩惠 HUD 始终按携带与阶段规则自动绘制，不提供客户端隐藏开关。
  - 玩法规则细节以[猫族关系规格](../../design/cat-bond.md)与[猫国灵体规格](../../design/spirit-cat-npc.md)为准。

## 4. 第三方联动清单

第三方联动均为可选依赖。构建使用 `compileOnly`，开发运行按需使用 `runtimeOnly`。

### 4.1 Jade（方块与实体信息）

- **发现入口**：[`JadePlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/jade/JadePlugin.java) 与 [`JadeClientPlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/plugin/jade/JadeClientPlugin.java) 位于 `common`，由 Fabric `jade` entrypoint 与 NeoForge `@WailaPlugin` 扫描装配。插件构造期不接触客户端类，客户端 Provider 仅在物理客户端调用 `registerClient` 时注册。
- **服务端/客户端分离**：`core.plugin.jade.JadeServerProviders` 注册服务端数据提供器，`client.plugin.jade.JadeClientProviders` 注册显示组件，共享字段声明在 `JadeData`。
- **提供信息**：展示当前玩家已解析的可疑方块战利品、封存内容和[淘洗点状态](../internals/panning-details.md#32-粒子音效与摇洗动画)。

### 4.2 JEI（配方与物品查询）

- **发现入口**：[`JeiPlugin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/plugin/jei/JeiPlugin.java) 位于 `common/client/`（Fabric 由 `fabric.mod.json` 声明，NeoForge 由 `@JeiPlugin` 注解自动发现）。
- **集成能力**：
  - `PotteryWheelJeiCategory` / `PotteryWheelJeiRecipe`：注册纹饰陶轮台配方类别（背景、进度与模型预览复用 UI Kit 绘制，原料槽位与焦点链接交由 JEI 管理）。
  - `JournalJeiGuiHandler`：将考古笔记网格与介绍页中的已解锁物品暴露为 JEI 可点击原料，支持 `U` / `R` 键跳转并沿 Screen 返回链回到笔记；浮层或输入捕获激活时不暴露底层物品。
  - JEI 运行时悬停原料查询配合 `ArchaeologyJournalKeyHandler`，支持在 JEI 列表上按笔记快捷键按注册名搜索。

### 4.3 Lootr 战利品追踪

- NeoForge 按 `lootr_neoforge_versions` 中的完整版本号启用整套追踪适配。安装范围外的 Lootr 时，基础模组照常加载，启动日志记录联动停用原因。Fabric 默认关闭该联动。 [构建属性](../../../gradle.properties) · [Mixin 接线](mixin.md)
- NeoForge 使用 Global Loot Modifier 注入考古奖励。启用 Fabric 联动时，Fabric 通过 `META-INF/services` 注册 Lootr Filter。 [Fabric Filter](../../../fabric/src/lootr/java/com/meteorite/unsuspiciousblock/core/plugin/lootr/LootrArchaeologyFilterProvider.java)
- Lootr 管理玩家或队伍的库存分配，本模组按实际领取者记录考古笔记。扫描仪的授权与具体库存绑定。 [战利品追踪数据流](../internals/journal-tracking.md)
- 原版埋藏宝藏和村庄武器匠表被同 ID 数据包替换时，Fabric 不追加内置奖励；NeoForge 在 Global Loot Modifier 仍启用时按表 ID 继续追加。数据包作者在 NeoForge 可通过全局修改器列表移除对应规则。

### 4.4 Trinkets / Curios（饰品栏）

使标本箱可装备到饰品栏并参与 `CarriedItems` 的便携携带查询：

| 平台 | 集成入口与组件 | 职责 |
|---|---|---|
| Fabric | [`FabricTrinketsIntegration`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/trinket/FabricTrinketsIntegration.java) | 由 `OptionalModIntegration` 反射加载；含 `SpecimenBoxTrinket`、`TrinketSlotResolver` 与 `TrinketsAccessoryHelper`（实现 `IAccessoryHelper`） |
| NeoForge | [`NeoForgeCuriosIntegration`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/curio/NeoForgeCuriosIntegration.java) | 入口及 `SpecimenBoxCurio`、`CuriosAccessoryHelper`（实现 `IAccessoryHelper`） |

当饰品模组未安装时，`IAccessoryHelper` 默认回退实现返回空流，玩法等价于“未装备饰品”。

### 4.5 Artifacts（饰品效果代理）

`fabric/platform/plugin/artifacts/` 与 `neoforge/platform/plugin/artifacts/` 各自实现 `ArtifactsSpecimenBoxSlotProvider`，使标本箱放在背包或饰品栏时能代理箱内兼容 Artifacts 饰品的装备效果。

### 4.6 Mekanism（制陶熔炼）

NeoForge 使用 Mekanism 报告的模组版本校验最低要求，由 `mekanism_neoforge_version_range` 声明。[Gradle 属性](../../../gradle.properties)

加载 Mekanism 时注册 `unsuspiciousblock:mekanism_pottery_smelting` 序列化器，内置配方使用 `neoforge:mod_loaded` 启用。配方以 `input` 声明坯件食材，以 `product` 选择 `pot` 或 `sherd`。机器使用原生 `TYPE_SMELTING` 查询并按实际输入烧制，陶片取组件中的纹饰物品，陶罐保留四面纹饰。Mekanism 的配方查看器展示普通陶罐及当前陶片标签中的可用纹样。[机器配方适配](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/mekanism/MekanismPotterySmeltingRecipe.java)

### 4.7 Create 鼓风机批量熔炼

NeoForge 通过 `requiredMods` 启用 Create 的独立 Mixin 配置，配置插件用早期模组列表筛选版本。Create 负责配方选择、加工计时以及掉落物和传送带的交付。选中制陶配方时，适配器用 `assemble` 计算当前坯件的产物，调用 Create 的拆栈方法保留数量和组件。空产物以 `null` 保留输入，其他配方委托原调用。[版本筛选](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/plugin/create/CreateFanMixinPlugin.java) · [产物接入](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/platform/mixin/compat/create/BlastingTypeMixin.java)

## 5. 扩展点

- **新增配置参数**：在对应配置入口定义字段、默认值和取值范围，同步更新读写与界面。战利品追踪字段在 common 实现，灵体猫和淘洗参数由平台实现。
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
