# 架构总览

> 三模块划分、初始化流程、构建系统与横切设计原则。
> **新加入的开发者先读完本文，再读 [平台抽象](platform-spi.md) 与 [注册架构](registration.md)，即可上手改代码。**
> 各子系统的包级细节不在本文，见 [开发者文档索引](../README.md) 的子系统地图。

## 1. 项目定位与技术栈

| 项目 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| Loader | Fabric 0.17.0+ / NeoForge 21.1.195+（双平台同源） |
| Java | 21 |
| 反混淆映射 | Mojang 官方映射 + Parchment（版本见 `gradle.properties`） |
| 构建系统 | Gradle（multiloader 自定义插件）+ Gradle Wrapper 版本见 `gradle/wrapper` |
| 当前版本 | 见 `gradle.properties` 的 `mod_version` |

模组核心围绕**考古探索 / 战利品发现记录 / 猫族关系**三条玩法主线展开，技术上是一个典型的 Architectury 风格多平台项目：绝大部分逻辑写在 `common`，Fabric 与 NeoForge 各自只提供平台接入。

## 2. 三模块划分

```
unsuspiciousBlock-1.21.1-multi/
├── common/      跨平台核心：玩法逻辑、注册清单、数据资源、Mixin、客户端 UI
├── fabric/      Fabric 接入：入口、注册实现、平台 SPI 实现、Fabric 独有 Mixin
└── neoforge/    NeoForge 接入：入口、注册实现、平台 SPI 实现、GLM/事件
```

### 2.1 common 模块

- 承载 **95% 以上的玩法代码**（实测约 490 个 Java 文件；重新核对用 `find common/src/main/java -name "*.java" | wc -l`）。
- **不 import 任何平台专属类**（`net.fabricmc.*`、`net.neoforged.*`）。这是项目的硬性约束，违反会在另一平台编译失败。
- 通过 [`platform/services/`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services) 定义的 SPI 接口访问平台能力，由 `fabric` / `neoforge` 提供实现（见 [平台抽象](platform-spi.md)）。
- 包含全部数据资源（`assets/`、`data/`）、Mixin 配置、客户端 GUI。
- 既含服务端逻辑也含客户端类（`client/` 包）。客户端类只允许在客户端入口或被环境守卫（`@Environment` / `Dist.CLIENT`）的代码路径引用。

### 2.2 fabric 模块

- 入口：[`UnsuspiciousBlockFabric`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockFabric.java)（`ModInitializer`）与 [`UnsuspiciousBlockFabricClient`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockFabricClient.java)（`ClientModInitializer`）。
- 用 `Registry.register` + Fabric API 事件回调完成注册与事件接入；**即时回写**注册结果（见 [注册架构](registration.md)）。
- 包含 Fabric 独有的 7 个 Mixin（6 个通用 + 1 个客户端）补齐原生事件缺失的能力（铁砧、刷拭、羊剪毛、区块扫描等），见 [Mixin](mixin.md)。
- 通过 `META-INF/services/` 注册 SPI 实现。

### 2.3 neoforge 模块

- 入口：[`UnsuspiciousBlockNeoForge`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockNeoForge.java)（`@Mod`）与 [`UnsuspiciousBlockNeoForgeClient`](../../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockNeoForgeClient.java)（`@EventBusSubscriber(Dist.CLIENT)`）。
- 用 `DeferredRegister` + `@SubscribeEvent` 完成注册与事件接入；**延迟回写**注册结果。
- 战利品注入使用 NeoForge 的 `IGlobalLootModifier`（GLM），而非 Fabric 的注入器方案。
- 铁砧修复使用 `AnvilUpdateEvent`，而非 Fabric 的 `AnvilMenuMixin`。
- 通过 `META-INF/services/` 注册 SPI 实现。

> **关键原则**：允许两个平台使用不同方案实现相同效果（如战利品注入、铁砧修复、骨块追踪）。只要玩家可见语义一致即可，不强求代码同构。

## 3. 初始化流程

### 3.1 服务端初始化

两个平台的入口都调用 [`UnsuspiciousBlockCommon.init()`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/UnsuspiciousBlockCommon.java)，该方法按固定顺序引导核心子系统：

```
平台入口构造/初始化
  └─ UnsuspiciousBlockCommon.init()
       ├─ AchievementManager.init(new VanillaAchievementHelper())  成就系统
       ├─ EnchantmentManager.init(...)        附魔框架
       ├─ EnchantmentEffects.registerAll()    注册附魔效果
       ├─ ModLootConditions.registerAnalysisHandlers()  战利品条件分析处理器
       ├─ EnchantmentRevealConditions.register()  附魔揭示条件
       ├─ LootTrackingBootstrap.registerListeners()  战利品追踪事件订阅
       └─ CatFavorManager.init(...)           猫族关系系统
```

**重要时序**：`UnsuspiciousBlockCommon.init()` 必须在平台完成战利品条件类型注册之后调用。Fabric 在 `onInitialize` 开头先注册工具附魔条件再调 init；NeoForge 也先通过 `ModLootConditions` 回写它的 `DeferredHolder`，再进入 common 初始化。

平台入口在 `init()` 前后还要完成：

- **注册**：遍历各 `ModXxx.forEach(registrar)` 清单（见 [注册架构](registration.md)）。
- **事件接入**：Fabric 用 `*Callback.EVENT.register`，NeoForge 用 `@SubscribeEvent`。
- **生命周期钩子**：服务器启动/停止、tick、chunk 生成、玩家登录等。
- **淘洗生成服务**：服务器 tick 与停止事件转交 `ShimmerSpawnService`，驱动淘洗点的自然生成（只投放水域变体）；世界生成通过统一的 `ShimmerFeature` 与两端群系注入接入原版地物阶段，介质由数据包里的变体 id 指定（见 [淘洗系统](../subsystems/panning.md)）。
- **玩家手册数据生命周期**：两端只把服务器启停/tick 和玩家登录/退出事件转交给 `JournalPlayerDataService`；玩家 NBT 进度与日志分片的物理存储保持独立（见 [考古笔记系统](../subsystems/journal.md)）。
- **战利品注入**：Fabric 注册各 `LootInjection`，NeoForge 通过 GLM JSON + 序列化器注册（见 [战利品表系统](../subsystems/loottable.md)）。
- **可选依赖**：通过 `OptionalModIntegration.instantiate()` 反射加载 Trinkets/Curios 集成（见 [平台抽象](platform-spi.md)）。

### 3.2 客户端初始化

两个平台客户端入口职责高度对称，都完成：

1. **按键绑定**注册（扫描模式切换、笔记、威慑切换、轻步切换）。
2. **实体/方块实体渲染器**与**模型层**注册（遍历 `ModEntityRenderers` / `ModModelLayers` 清单）。
3. **Screen**注册（考古笔记、标本箱、陶轮）。
4. **S2C 接收器**注册（遍历 `ModPayloads.Client.S2C_PAYLOADS` 清单）。
5. **Tooltip 组件**注册（标本箱内容预览）。
6. **HUD / 世界渲染**注册（猫之恩惠 HUD、范围扫描高亮、保护罩）。
7. **客户端 tick** 驱动（笔记按键、客户端状态、扫描高亮等）。
8. **断连重置**（清理客户端状态）。

> 细节见 [客户端与 GUI](../subsystems/client-ui.md)。按键的具体键位以代码中的 `KeyMapping` 定义为准。

### 3.3 NeoForge 的时序陷阱

NeoForge 端有几处需要留意的时序问题，代码中已有注释说明：

- **Item 静态字段回写**：`DeferredRegister` 在 `FMLCommonSetupEvent` 阶段才实例化 Item，因此 Item 的回写延迟到 `syncCommonItemRefs` 的 `enqueueWork` 中。而 Block/BlockEntity/Effect/Sound/Entity 的 `DeferredHolder` 本身即 `Supplier`，可在 static 块立即回写。
- **MenuType 获取兜底**：`RegisterMenuScreensEvent` 可能在 `enqueueWork` 之前触发，此时 `SpecimenBoxMenu.TYPE` 静态字段可能为 null，因此客户端通过 `UnsuspiciousBlockNeoForge.getSpecimenBoxMenuType()` 直接从 `DeferredHolder` 取值兜底。

完整的回写时机对照见 [注册架构](registration.md)。

## 4. 构建系统

### 4.1 multiloader 插件

`buildSrc/` 提供两个自定义 Gradle 插件：

- `multiloader-common`（[`buildSrc/src/main/groovy/multiloader-common.gradle`](../../../buildSrc/src/main/groovy/multiloader-common.gradle)）：配置 Java 工具链、sources/javadoc jar、编码、仓库、capability 声明、`processResources` 占位符展开、发布配置。
- `multiloader-loader`：加载器子模块共用配置。

`processResources` 的 `expandProps` 是**改构建元数据时的唯一入口**：`fabric.mod.json`、`neoforge.mods.toml`、`*.mixins.json`、`pack.mcmeta` 里的 `${...}` 占位符都由它展开。新增占位符时在 `expandProps` 里加一项，否则文件里会留下字面量 `${xxx}`。

### 4.2 各平台构建

| 维度 | common | fabric | neoforge |
|---|---|---|---|
| 插件 | `multiloader-common` + `net.neoforged.moddev` | `multiloader-loader` + `fabric-loom` | `multiloader-loader` + `net.neoforged.moddev` |
| 映射 | moddev 提供 Mojang + Parchment | Loom layered（Mojang + Parchment） | moddev 提供 Mojang + Parchment |
| 访问扩展 | AccessTransformer（`META-INF/accesstransformer.cfg`） | AccessWidener（`unsuspiciousblock.accesswidener`） | AccessTransformer（同 common） |
| DataGen | — | — | `runData` 任务，输出到 `src/generated/resources` |

> **Gradle 版本已被锁定**：升到 8.14.5 会让 fabric `remapJar` 产出空包；Loom 1.11+ 才支持 8.14。改动 Gradle / Loom 版本前先验证 `remapJar` 产物非空。

### 4.3 Lootr 可选兼容

Lootr 兼容是**构建时可选**的，通过 `gradle.properties` 的两个属性控制：

```properties
lootr_compat_fabric=false
lootr_compat_neoforge=true
```

关闭时 `common/build.gradle` 会排除 `mixin/compat/lootr/`、`plugin/lootr/` 下的代码、`unsuspiciousblock.lootr.mixins.json` 与对应的 `META-INF/services` 文件；`multiloader-common.gradle` 还会从 `neoforge.mods.toml` 模板中剔除 Lootr 依赖与 `[[mixins]]` 块。判定与生效细节见 [Mixin](mixin.md) 与 [配置与第三方联动](config-and-integrations.md)。

> 当前发布配置：Fabric 不带 Lootr 兼容，NeoForge 带。

### 4.4 可选联动依赖

Trinkets / Curios / Artifacts / ModMenu / Jade / JEI 均为**可选联动**，统一采用 `compileOnly`（编译期引用 API）+ `runtimeOnly`（仅开发运行环境加载）模式，发布时不强制玩家安装。依赖缺失时的反射加载机制以 [平台抽象](platform-spi.md) 为权威；各联动的集成类清单见 [配置与第三方联动](config-and-integrations.md)。

## 5. 横切设计原则

以下原则贯穿整个代码库，开发时务必遵守：

1. **common 零平台依赖**：`common/` 不得 import 任何平台类。需要平台能力时定义 SPI 接口（见 [平台抽象](platform-spi.md)）。
2. **客户端/服务端分离**：服务端代码严禁引用 `client/` 下的类。客户端类只在客户端入口或被环境守卫的路径引用。
3. **清单 + 回调注册**：新增可注册内容只在 common 的 `REGISTRY_MANIFEST` 加一行，平台代码无需改动（见 [注册架构](registration.md)）。
4. **优先 Event API，少用 Mixin**：Fabric 端原生事件不足时才用 Mixin，且 Mixin 仅作入口，不写业务逻辑（见 [Mixin](mixin.md)）。
5. **数据驱动优先**：可枚举内容（礼物、交易、结构、worldgen、附魔、配方）交数据包，可调强度交配置，身份/关系语义保持硬编码（见 [配置与第三方联动](config-and-integrations.md)）。
6. **注释规范**：类注释用 `/***/`，方法注释用 `//`，注释用简体中文。
7. **i18n**：所有 HUD/GUI 文本用本地化 key，`en_us.json` 与 `zh_cn.json` 同步更新（见 [文本格式规范](text-format.md)）。

## 6. 数据资源结构

```
common/src/main/resources/
├── assets/unsuspiciousblock/      blockstates / models / textures / lang / sounds
└── data/unsuspiciousblock/
    ├── advancement/               进度（adventure/challenges/recipes）
    ├── enchantment/               附魔定义
    ├── journal_categories/        考古笔记目录分类规则
    ├── loot_table/                战利品表（blocks/chests/gameplay/usb）
    ├── merchant_cat_trades/       猫猫商人交易定义
    ├── recipe/                    配方
    ├── structure/  worldgen/      结构与世界生成
    └── tags/                      物品/世界生成标签
```

> 源码包级结构不在本文维护：各包的职责见 [开发者文档索引](../README.md) 的子系统地图，各子系统的具体类与入口见对应篇的「代码地图」。**新增子系统时只改索引与新建一篇，不要往本文塞包清单。**

## 7. 相关文档

- [开发者文档索引](../README.md) —— 任务导航与子系统地图
- [平台抽象](platform-spi.md) —— Services SPI 机制与可选依赖反射加载
- [注册架构](registration.md) —— 清单 + 回调模式与新增内容流程
- [Mixin](mixin.md) —— 三套 mixin 配置与全部注入点
- [网络与同步](network.md) —— payload 清单
- [配置与第三方联动](config-and-integrations.md) —— 配置项全表与联动清单
- [文本格式规范](text-format.md) —— 游戏内文本结构与语义色表
