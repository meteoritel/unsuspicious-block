# 淘洗系统变体化规划

> 状态：**规划中，尚未实施**。本文覆盖金淘盘、黑曜石淘盘、幽微的光（下界岩浆淘洗点）与幸运产出分化四项扩展，记录 22 项设计裁定、已经源码核实的技术事实与分阶段实施路线。
>
> 现有淘洗机制的唯一权威文档仍是 [`docs/dev/panning.md`](../dev/panning.md)；本文描述的是**目标架构**，两者在实施完成前会有差异。
>
> **复核修订（第二轮）**：§3.2 补充泛型放宽在 `ModEntityRenderers` 处的编译约束；§3.4 修正 `Level.getSeaLevel()` 硬编码 63 的事实与影响范围；§4.3 要求 `luckByVariant` 惰性求值；§4.9 补齐改动清单；§6 新增 10~12 条风险。
>
> **复核修订（第三轮）**：变体载体裁定为**两个 EntityType**（§5 裁定 1 已记录采纳理由与被否决的备选）。§3.2 补充 `defineId` 约束与裁定说明；§4.8 把 P1 拆为 P1a（变体数据层，实体不动）与 P1b（实体层次拆分），并把 `RendererEntry` 泛型改造、工厂换构造器、渲染器清单补行分别落到对应阶段；§6 新增第 13 条（实体层面的反向降级代价）。
>
> **实施记录（P1a 已实机验收，P1b / P2 / P3 已完成，构建通过，待一次合并验收）**：新增 §3.9 记录「冰中的淘洗点不可淘洗」是刻意保留的特性。P1a 的实机验收已通过，未发现行为差异。实施中确认了两处对计划的收窄：`AnchorRule.isRenderableSurface` 实现为 `default` 方法（由 `isAnchorBlock` 派生）而非抽象方法；`surfaceOffset` 只服务实体演出，渲染器继续用 `fluid.getHeight(...)`，以免引入 0.014 格的可见变化。另有一处刻意的语义放宽：铜盘修复材料由硬编码 `minecraft:copper_ingot` 改为 `c:ingots/copper` 标签（`PanProfile.repairTag` 的必然结果）。

## 一、现状

### 1.1 现有玩法

玩家在河流中发现依附水面的「闪烁的光」，用淘盘长按右键淘洗，消耗次数并抽取沉积物产出；采空后消散或保留恢复，自然生成按维度上限与节拍投放，新区块由地物生成。

### 1.2 现有实现

| 组件 | 文件 | 职责 |
|---|---|---|
| 实体 | `entity/ShimmerEntity.java` | 锚定水方块、寿命、次数恢复、账本登记、淘洗状态机、粒子 |
| 落点判定 | `pan/ShimmerPlacement.java` | 河流群系识别、开阔水面采样、水与冰的依附校验 |
| 生成服务 | `pan/ShimmerSpawnService.java` | 自然生成节拍、特殊再生、手动生成、清除 |
| 地物 | `pan/ShimmerRiverFeature.java` | 新区块写入实体 NBT |
| 账本 | `pan/ShimmerLedger.java` | 每维度持久化位置、来源、寿命、过期标记与采空冷却 |
| 结算 | `pan/PanningLootService.java` | 抽取战利品表、抛出产出、交付考古笔记 |
| 工具 | `item/CopperPanItem.java` | 长按淘洗流程、耐久消耗、再生概率钩子 |
| 客户端 | `client/renderer/ShimmerSurfaceRenderer.java`、`client/pan/*` | 贴水波光、粒子分档、摇洗动画与水声 |

### 1.3 与「水 + 铜盘」硬绑定的位置

这些是实现变体时必须改造的耦合点：

- **实体反向依赖具体物品**：`ShimmerEntity.java:224` 直接 `import CopperPanItem`，交互时 `instanceof CopperPanItem`。
- **介质写死**：`ShimmerEntity.canPan()` 只认 `Blocks.WATER`，`isFrozen()` 只认 `Blocks.ICE`，`waterSurfaceY()` 写死 0.875 / 1.0。
- **表现写死**：客户端粒子用 `SPLASH` / `FISHING` / `END_ROD`；消耗音效用 `AMETHYST_BLOCK_CHIME`；起手用 `BUCKET_FILL`；波光颜色常量 `255,223,115` 与 `255,249,213`；`PanningSound` 用 `WATER_AMBIENT` 与 `+0.875` 偏移。
- **落点判定写死**：`ShimmerPlacement` 整体围绕 `Blocks.WATER` / `Blocks.ICE` / `BiomeTags.IS_RIVER` / 世界海平面。
- **生成写死**：`ShimmerSpawnService` 固定用 `ModEntities.SHIMMER`；`ShimmerRiverFeature` 用 `NoneFeatureConfiguration`，`createWorldgenTag` 静态写死实体 id。
- **产出写死**：`PanningLootService.RIVER_PANNING_LOOT_TABLE` 不可替换。
- **工具写死**：`PanningAnimation` 两处 `instanceof CopperPanItem`；`PanningVisuals.register()` 只给 `COPPER_PAN` 注册模型帧属性。
- **账本单计数**：`ShimmerLedger` 只有 `Source` 一个分类维度与单个 `naturalCount` 计数器。
- **配置单组**：`IPanningConfig` 只有一组全局参数。

### 1.4 一处现存缺陷

`CopperPanItem.onUseTick` 只校验 `target.canPan()`，而 `canPan()` 只反映实体自身状态，不校验工具是否支持该变体。引入变体后若不修，铜盘会持续「淘洗」幽微的光。

## 二、计划目的

### 2.1 新增内容

| 项 | 说明 |
|---|---|
| 金淘盘 `gold_pan` | 采水点；淘洗后按概率再生新的闪烁的光；产出品质高于铜盘 |
| 黑曜石淘盘 `obsidian_pan` | 既能采水点也能采幽微的光；采水点时产出品质低于铜盘；无再生效果 |
| 幽微的光 `glimmer` | 依附岩浆的淘洗点，只出现在下界，只能被黑曜石淘盘采集，波光为紫色 |
| 幸运产出分化 | 三把盘通过原版幸运机制在**同一张表**上分化，而不是各自一张表 |

### 2.2 架构目标

- 变体差异集中为可注册的**数据**，新增变种的成本是「注册项 + 一条数据 + 资源」，而不是「新类 + 改 switch」。
- 消除实体与具体物品类之间的双向依赖，改为工具与点的**能力交集**判定。
- 生成侧的水域/岩浆差异下沉为可替换的算法片段，落点采样骨架只保留一份。
- 客户端表现参数由变体唯一决定，**变体本身不需要任何网络同步字段**。
- 唯一的例外是「谁正在淘洗哪个点」：为了让远端玩家也能显示正确的摇洗帧，需要同步一个淘洗者 UUID 集合（见 §4.5）。

### 2.3 明确不做（本轮范围外）

- 岩浆点的运行时自然生成（节拍、维度上限、间距、采空冷却）——见 §4.6 的生成策略裁定。
- 按变体分组的配置项：本轮所有参数全局共用，仅新增两个幸运相关的标量。
- 淘盘专属附魔（金盘的幸运加成先走工具固定值，附魔作为后续扩展点）。
- 浮冰、蓝冰等其它依附面。

## 三、技术原理

本节记录已经过源码或数据包核实的机制事实，它们是路线选择的依据。

### 3.1 为什么变体差异用数据注册表而不是继承树

三把盘之间的差异只有：可采目标集、耐久、修复材料、幸运加成、再生概率、贴图。幽微的光与水点之间的差异只有：依附方块、生成域、波光颜色、粒子、音效、产出表。**全部是参数，没有一条是新算法。**

用继承表达参数会得到「子类里全是常量赋值、新增变种要改工厂与 switch」；用接口表达参数会让每个变体背一个实现类的样板。真正值得抽成接口的只有 `AnchorRule`（怎么判断依附）与 `SpawnDomain`（怎么找落点）这两处。

同时，Minecraft 的 `EntityType.Builder.of` 需要具体构造器，`Item` 与 `EntityType` 也以「类 + 注册 id」为单位，所以**仍然必须有类**——结论不是「继承还是接口」二选一，而是分层使用不同工具。

### 3.2 变体载体为什么用两个 EntityType

- **旧存档零迁移**：现有实体 id 保持 `unsuspiciousblock:shimmer` 不变，新变体是新 id，账本与 NBT 的旧格式无需迁移。
- **客户端免同步**：表现参数由 `entity.getType()` 唯一决定，`ShimmerSurfaceRenderer` 直接查本地注册表即可，不必新增 `EntityDataAccessor`。
- **选择器与数据包可区分**：`@e[type=...]`、`/place feature`、生成蛋、后续扩展都按类型工作。
- **Jade 无需重复注册**：见 §3.5。

代价是渲染器注册链必须改造，**但泛型不必放宽**（P1b 实施修正）：

- `EntityType<T>` 的泛型不协变，`EntityType<WaterShimmerEntity>` 装不进 `EntityType<ShimmerEntity>`，因此 `ModEntities.SHIMMER` 按具体子类声明为 `Supplier<EntityType<WaterShimmerEntity>>`（`GLIMMER` 同理）。
- 曾按本节原方案尝试放宽为 `Supplier<EntityType<? extends ShimmerEntity>>`，**编译失败**，但失败原因与原先的推断不同：`EntityEntry` 清单里的 setter 是隐式类型 lambda，javac 会在类型推断完成前检查其 lambda 体，此时 `T` 仍只是推断变量，`Supplier<EntityType<T>>` 到 `Supplier<EntityType<? extends ShimmerEntity>>` 无法被证明为合法。改成按具体子类声明后 setter 类型精确匹配，问题消失，也不需要任何强制转换。
- `ModEntityRenderers` 仍然必须改：`RendererEntry<T>` 同时接收 `Supplier<EntityType<T>>` 与 `EntityRendererProvider<T>`，而 `ShimmerRenderer extends EntityRenderer<ShimmerEntity>` 要求 `T = ShimmerEntity`，两个参数无法共存。改为 `Supplier<EntityType<? extends T>>` 后成立——这恰好与两个平台 API 的既有签名一致（Fabric `EntityRendererRegistry.register` 与 NeoForge `EntityRenderersEvent.registerEntityRenderer` 都已核实为 `EntityType<? extends T>`），因此项目自己的 `EntityRendererRegistrar` 也一并对齐，平台侧零改动、零强制转换。
- `ModEntities.createShimmerType()` 的 `EntityType.Builder.of(ShimmerEntity::new, ...)` 必须换成具体子类构造器（抽象基类无法实例化），`GLIMMER` 同理。

**`client/renderer/ModEntityRenderers.java` 原先漏在 §4.9 的改动清单外，但它是必须改的**：未登记渲染器的实体类型在客户端 `EntityRenderDispatcher.getRenderer` 取不到渲染器（返回 null，不抛异常），随后渲染时 NPE。

**关键约束**：基类必须保留 `ShimmerEntity` 这个名字（由具体类改为抽象类），这样所有 `instanceof ShimmerEntity` 的位置——渲染器、声音控制器、生成服务、Jade、调试指令——都零改动。

**另一条关键约束**：`SynchedEntityData.defineId(...)` 的三个 accessor 必须继续定义在（抽象）基类里并绑定 `ShimmerEntity.class`，`WaterShimmerEntity` 与 `GlimmerEntity` **不得覆写 `defineSynchedData`**。原版 `LivingEntity` / `Mob` 就是这个模式（各自用本类 `defineId`，子类只追加、不重定义）；一旦子类各自定义，两类的合成数据表会错位。

**本方案已裁定采纳**（见 §5 裁定 1）：上面的编译面与泛型连锁属于已知代价，接受。曾平行评估过「单 EntityType + 同步变体 id」的方案，被否决——理由是本文档的定位是「变体 = 注册项」，让幽微的光在数据包、选择器与外部工具眼里是一个独立实体，比省下 P1 的改动面更符合长期形态；同时评估确认了单实体方案在反向降级上更温和（缺字段按水、首 tick 自动消散注销，而本方案下旧版会静默丢弃实体并残留账本条目，见 §6.13）。若将来新增第三种依附面时嫌弃「新变种要写新类」，可以再回到这个议题，届时切换只需改实体类、`ModEntities`、`ModEntityRenderers` 三处。

### 3.3 原版幸运机制（源码核实）

这是金盘与黑曜石淘盘产出分化的全部依据。

**条目有效权重**（`LootPoolSingletonContainer.EntryBase#getWeight`）：

```java
public int getWeight(float luck) {
    return Math.max(Mth.floor(this.weight + this.quality * luck), 0);
}
```

**零权重条目被剔除**（`LootPool#addRandomItem`）：

```java
int w = entry.getWeight(context.getLuck());
if (w > 0) {
    list.add(entry);
    totalWeight.add(w);
}
```

**池的抽取次数也受幸运影响**（`LootPool#addRandomItems`）：

```java
int rolls = this.rolls.getInt(context) + Mth.floor(this.bonusRolls.getFloat(context) * context.getLuck());
```

三条推论：

1. `weight: 0` + `quality: N` 的条目在 `luck <= 0` 时权重为 0，会被原版**直接剔除出候选列表**，任何人都抽不到——这就是「幸运门槛」，是让某些产物只属于金盘的手段。
2. `weight: W` + `quality: Q` 的条目随幸运线性增减，`luck < 0` 时权重下降甚至归零，是让**负幸运可见**的唯一途径（门槛系对负值没有额外惩罚，因为它本来就是 0）。
3. `bonus_rolls` 是第二条独立通道，但**绝不能加在基础产出池上**：黑曜石盘的负幸运会让 `rolls + floor(bonus_rolls × luck)` 变成 0，把基础产出直接清零。必须独立成池。

现有代码已经在传 `withLuck(player.getLuck())`，只是 `river.json` 里没有 `quality` 字段，所以幸运至今没有任何效果。

### 3.4 下界世界生成（源码核实）

- 下界维度的 `ChunkGenerator.getSeaLevel()` 返回 **32**（`NoiseBasedChunkGenerator` 直接返回 `NoiseGeneratorSettings.nether` 的 `sea_level`；实测三张预设：主世界 63、下界 32、末地 0。`Level.getSeaLevel()` 里那个 63 与主世界生成器取值**碰巧相等**，这是下面这条陷阱至今没暴露的原因）。
- 下界 `aquifersEnabled = false`，流体由 `FluidStatus(32, LAVA)` 决定，`at(y)` 返回岩浆当且仅当 `y < 32`，因此**岩浆海最上层方块在 y = 31**。
- **`Level.getSeaLevel()` 是硬编码的 `return 63;`，与维度无关**（`ServerLevel`/`ClientLevel` 均未覆写，`WorldGenRegion` 只是转发）。所以现有搜索区间 `[seaLevel + 1, seaLevel - 3]` 只有在**取生成器**的 sea level 时才等于下界的 `[33, 29]`、才覆盖 y = 31：
  - 地物路径 `ShimmerRiverFeature` 用的是 `context.chunkGenerator().getSeaLevel()` → 下界正确得到 32，**这条路径没问题**；
  - 运行时路径 `ShimmerSpawnService` 用的是 `level.getSeaLevel()`（`pickLoadedChunk` 与 `trySpawnInChunk` 各一处）→ **在下界恒为 63**，会去搜索 y = 64~60，永远找不到 y = 31 的岩浆海面。
  - 结论：§4.3 的 `SpawnDomain.surfaceSearchCenter` 必须由 `chunkGenerator().getSeaLevel()`（或变体自带的常量）供给，并修正上述两处调用点。按 §4.6 的裁定幽微的光只走世界生成，所以这件事不影响 P5 落地；但它是将来启用岩浆点运行时生成时的直接拦路石。
- `BiomeTags.IS_NETHER`（`minecraft:is_nether`）存在，内容为 5 个下界群系。
- **不能**把 `seaLevel == 32` 当作「这是下界」的判据——它只是该 `NoiseGeneratorSettings` 的常量。

### 3.5 Jade 的类匹配语义（字节码核实）

项目使用 Jade 15.10.5。`WailaCommonRegistration.registerEntityDataProvider` 与 `WailaClientRegistration.registerEntityComponent` 都把 provider 存入 `snownee.jade.impl.lookup.HierarchyLookup`，其 `getInternal` 沿 `Class.getSuperclass()` **逐级向上递归**查找（不遍历接口）。

结论：注册在 `ShimmerEntity.class` 上的 provider 会**自动覆盖**两个子类实体类型，`JadePlugin` 无需改动，幽微的光会直接复用现有的寿命行与剩余次数行。

### 3.6 笔记目录的自动收录规则（源码核实）

- `ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES` 含 `unsuspiciousblock:gameplay/panning/`，`LootTablePattern` 对以 `/` 结尾的路径做前缀匹配，`LootTableNames.isArchaeologyLootTable()` 据此把表加入根表集合。
- `journal_categories/panning.json` 的 `special_rules` 只约束 `namespaces: ["unsuspiciousblock"]` 与 `path_prefixes: ["gameplay/panning/"]`，**没有 `type` 约束**，匹配逻辑是 `tableId.getPath().startsWith(prefix)`。

结论：新增 `unsuspiciousblock:gameplay/panning/lava` 会自动被收录并归入「淘洗」分类，笔记侧零代码改动。

**注意**：该前缀列表是**配置默认值**，Fabric 端读配置项 `archaeology_path_prefixes`。若旧存档持久化过不含该前缀的旧列表，新表将不被自动收录。落地后需实机确认目录中出现该表。

### 3.7 通用标签（数据包核实）

以下标签由 Fabric API 与 NeoForge 各自的数据包提供并填充原版物品，配方可直接引用：

| 标签 | 内容 |
|---|---|
| `c:ingots/gold` | `minecraft:gold_ingot` |
| `c:nuggets/gold` | `minecraft:gold_nugget` |
| `c:obsidians` / `c:obsidians/normal` | `minecraft:obsidian` |
| `c:obsidians/crying` | `minecraft:crying_obsidian` |

### 3.8 必须遵守的边界

- **生成期只能读本区块**：`WorldGenRegion.getBlockState` 越界会抛 `ReportedException`；FEATURES 阶段只保证距离 0/1 的区块方块数据有效。现有实现靠「内缩 1 格 + 3×3 检查不跨区块」保证安全，改造时必须保留。
- **`EntityDataSerializers` 没有 UUID 序列化器**：介质同步用 `STRING` 拼接 UUID 列表，或拆成两个 `LONG`。
- **准星命中结果只有本地玩家有**：`Minecraft.crosshairPickEntity` 在远端玩家身上不可用，所以远端玩家要正确显示介质必须由服务端同步。
- **过期的世界点不占名额**：账本与实体不一致时以实体为准，改造后仍需保持。

### 3.9 冰中的淘洗点不可淘洗（刻意保留的行为，实机确认）

冰面属于水域变体的可依附介质（`WaterAnchor.isAnchorBlock` 认 `Blocks.ICE`），因此冰中的点能生成、能存活、也能通过 `canPan()` 的状态校验，但**玩家永远淘不到它**：

- 起手与持续判定都走 `ProjectileUtil.getHitResultOnViewVector`，该射线以 `ClipContext.Block.COLLIDER` 在第一个带碰撞箱的方块处截断；
- 实体包围盒自依附方块底部起高 0.9，整体落在实心冰块内部，射线止于冰块顶面，够不到实体；
- 水方块没有碰撞箱，射线可穿透，所以只有冰面会触发这个现象。

**这是刻意保留的特性，不是缺陷**——水结冰本就不该让玩家隔着冰面淘洗。它依赖两条隐式不变量：实体包围盒高度不超过 1；依附介质是完整碰撞箱方块。因此改动 `ModEntities.createShimmerType()` 的 `.sized(...)`，或新增带碰撞箱的依附介质，都会改变这个行为。岩浆没有碰撞箱，幽微的光不受影响。

由此产生一处刻意的不对称：冰面的点在账本里**照常占用名额**（worldgen 来源不计入自然上限，自然来源与特殊再生则会计入），只是无人能采集它。若将来需要收口，可在落点采样时按「依附介质是否带碰撞箱」排除。

## 四、技术路线

### 4.1 分层设计

| 层 | 内容 | 工具 |
|---|---|---|
| 机制骨架 | 锚定、寿命、次数恢复、账本登记、淘洗状态机、NBT | **继承，深度固定为 1 层** |
| 变体差异 | 依附介质、生成域、波光、产出表、可采性 | **数据注册表（record + ResourceLocation）** |
| 真算法片段 | 落点搜索、依附判定 | **接口 `AnchorRule` / `SpawnDomain`** |
| 工具差异 | 可采目标集、耐久、修复材料、幸运加成、再生概率 | **数据档案 `PanProfile` + 基类 `PanItem`** |

**子类里不允许出现 `if (介质)` 分支**——一旦出现，说明该差异应该下沉进变体数据。

### 4.2 目标结构

```
pan/
  ShimmerVariants.java            // 注册表：ResourceLocation -> ShimmerVariant
  variant/
    ShimmerVariant.java           // record：id / anchor / spawnDomain / glow / lootTable / entityType
    AnchorRule.java               // 接口
    SpawnDomain.java              // 接口
    GlowStyle.java                // record：颜色、粒子、音效（纯数据）
    WaterAnchor / LavaAnchor
    RiverDomain / NetherDomain
  ShimmerFeature.java             // 参数化地物（原 ShimmerRiverFeature）
  ShimmerFeatureConfig.java       // 带 codec 的地物配置，携带变体 id
item/
  PanItem.java                    // 基类 = 标记 + 长按流程
  PanProfile.java                 // record：harvestTargets / repairTag / luckByVariant / regenerationChance
entity/
  ShimmerEntity.java              // 抽象基类（保留原名，见 §3.2）
  WaterShimmerEntity.java         // 只实现 getVariant()
  GlimmerEntity.java              // 只实现 getVariant()
client/pan/
  PanningMediumIndex.java         // 玩家 UUID -> 变体，供物品属性取介质
```

### 4.3 关键类型

**`ShimmerVariant`**（数据注册表条目）

```java
public record ShimmerVariant(
    ResourceLocation id,
    AnchorRule anchor,                              // 依附判定与液面偏移
    SpawnDomain spawnDomain,                        // 海平面基准与群系预筛
    GlowStyle glow,                                 // 颜色、粒子、音效
    ResourceKey<LootTable> lootTable,               // 产出表由变体决定
    Supplier<EntityType<? extends ShimmerEntity>> entityType
) {}
```

**`AnchorRule`**（接口，两处真算法之一）

```
isValid(LevelReader, BlockPos)          依附方块 + 上方空气
surfaceOffset(LevelReader, BlockPos)    相对方块底部的液面高度
isFrozenAt(LevelReader, BlockPos)       冻结静态相位（岩浆恒 false）
isRenderableSurface(...)                客户端波光可绘制判定
```

**`SpawnDomain`**（接口，两处真算法之二）

```
surfaceSearchCenter(...)   取 chunkGenerator().getSeaLevel()：主世界 63、下界 32
acceptsBiome(...)          河流用 IS_RIVER；下界用 IS_NETHER
acceptsChunk(...)          区块级廉价预筛；下界无预筛直接返回 true
```

注意 `surfaceSearchCenter` **不能**取 `level.getSeaLevel()`——它硬编码返回 63（见 §3.4），下界会错到 y = 64~60。`ShimmerSpawnService` 现有的两处 `level.getSeaLevel()` 调用点要一并换成生成器取值。

**`PanProfile`**（工具数据）

```java
public record PanProfile(
    Set<ResourceLocation> harvestTargets,           // 可采变体集合
    TagKey<Item> repairTag,                         // 铁砧修复材料
    Map<ResourceLocation, DoubleSupplier> luckByVariant,  // 按目标变体分档的幸运加成，缺省 0
    DoubleSupplier regenerationChance               // 读全局配置
) {}
```

**`luckByVariant` 必须惰性求值（不能存 `Double`）**：`NeoForgePanningConfig` 背后是 `ModConfigSpec` 的 `IntValue`/`DoubleValue`，未加载时 `.get()` 抛 `IllegalStateException: Cannot get config value before config is loaded`；而 `ModItems` 的物品工厂在注册表填充期（服务器启动之前）就会被调用。现有代码从不在注册期读 panning 配置（`getPanUses()` 只在实体构造与 tick 时调用）正是同一个原因。`regenerationChance` 已经用了 `DoubleSupplier`，`luckByVariant` 要与它保持一致。

| 工具 | harvestTargets | luckByVariant | regenerationChance |
|---|---|---|---|
| 铜淘盘 | `{water}` | `{}` | 0 |
| 金淘盘 | `{water}` | `{water: +gold_pan_luck_bonus}` | `gold_pan_regeneration_chance` |
| 黑曜石淘盘 | `{water, glimmer}` | `{water: -obsidian_pan_luck_penalty}` | 0 |

**判定改为交集，消除双向依赖**：

```java
// ShimmerEntity.interact 与 PanItem.onUseTick 共用；PanItem 本身即是「是淘盘」的判据
if (!(stack.getItem() instanceof PanItem pan) || !pan.profile().supports(this.getVariant())) {
    return InteractionResult.PASS;
}
```

`PanProfile` 由 `PanItem` 持有，不再需要额外的 `Item -> Profile` 注册表；`PanningAnimation` 与 `PanningVisuals` 的判据也从 `CopperPanItem` 改为 `PanItem`。

### 4.4 战利品表结构

产出表由变体决定：水点用 `gameplay/panning/river`，幽微的光用 `gameplay/panning/lava`。两张表都自动归入笔记的「淘洗」分类（§3.6）。

`river.json` 的条目分档：

| 池 | 配置 | 铜盘（luck 0） | 金盘（luck +1） | 黑曜石盘（luck -0.5） |
|---|---|---|---|---|
| 沉积物 | `rolls:1`，现有 7 条，`quality:0` | 基础产出 | 基础产出 | 基础产出 |
| 幸运发现 | `rolls:1`，`weight:0 / quality:2~4` | **抽不到**（零权重被剔除） | 权重 2~4 | 抽不到 |
| 品质倾斜 | `rolls:1`，`weight:2~4 / quality:2~3` | 基础权重 | 权重翻倍 | 权重腰斩 |
| 额外一淘 | `rolls:0 / bonus_rolls:1` | 0 次 | 1 次 | 0 次 |
| 绿宝石 / 钻石 | `random_chance` 0.08 / 0.01 | 不变 | 不变 | 不变 |

`lava.json` 本轮先放占位最小表，后续再设计内容。

### 4.5 客户端表现

- **波光**：颜色、液面判定、冻结相位全部改由变体的 `AnchorRule` 与 `GlowStyle` 提供；紫色取 `RGB 200,120,255`，亮芯 `240,205,255`，沿用现有 48/28/12 三档数量与错峰明灭。
- **粒子**：世界点辨识粒子与工作水花改为紫色粒子，不再固定 `SPLASH` / `FISHING` / `END_ROD`。
- **音效**：起手改用 `BUCKET_FILL_LAVA`，摇洗循环用 `LAVA_AMBIENT`。
- **摇洗帧**：新增 `panning_medium` 物品属性（0 = 水，1 = 岩浆），与 `panning_frame` 组合成双谓词覆盖。只有黑曜石盘需要两套帧——铜盘与金盘对幽微的光会直接 `PASS`，永远不会进入使用状态。
- **介质同步**：`ShimmerEntity` 用原版 `STRING` 序列化器同步「正在淘洗的玩家 UUID 集合」，服务端维护 `Map<UUID, Long>` 并按 2 刻超时剔除，集合变化时才发包。客户端 `PanningMediumIndex` 由 `ShimmerEntity.clientTick()` 自注册维护，物品属性据此查表。这样远端玩家（第三人称、多人同屏）也能显示正确介质。该集合同时让 `PanningSoundController` 不再依赖模糊的「有人在淘洗」判定。

### 4.6 生成策略裁定

- **水点**：维持现有两条来源——运行时自然生成（节拍、维度上限、间距、采空冷却）与河流地物生成。
- **幽微的光**：**纯世界生成地物**，不做运行时自然生成。理由：下界岩浆海面积是河流的数十倍，运行时生成会让玩家一进下界迅速顶到全局上限；且自然点的 20~40 分钟寿命语义与「下界探索时发现地标」的体验冲突。密度只由地物 `chance` 一个旋钮控制。
- 幽微的光保留完整的 natural / worldgen / special 三分类机制（`Source` 与变体是两条正交的轴），只是不启用它的 natural 入口；实际只会出现 worldgen（地物）与 manual（调试）。
- 采空冷却仍会写入账本，但只有自然生成会读取它，对幽微的光无行为影响——保留是为了路径统一。

### 4.7 地物与注册

- `ShimmerRiverFeature` 泛化为 `ShimmerFeature`，配置从 `NoneFeatureConfiguration` 换成带 codec 的 `ShimmerFeatureConfig`（携带变体 id）。
- Feature **类型**注册名由 `unsuspiciousblock:river_shimmer` 改为 `unsuspiciousblock:shimmer`；placed_feature 的 id 仍保留 `river_shimmer`（Fabric 注入代码引用不变），新增 `nether_shimmer`。
- 下界注入：Fabric 加一行 `BiomeModifications.addFeature(BiomeSelectors.tag(BiomeTags.IS_NETHER), GenerationStep.Decoration.VEGETAL_DECORATION, ModFeatures.NETHER_SHIMMER)`；NeoForge 新增 `biome_modifier/add_nether_shimmer.json`。
- 下界 `rarity_filter` 的 `chance` 初始取 100（比水点的 50 稀一倍），后续用数据包调。

### 4.8 分阶段实施

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **P1a 变体数据层** | 引入 `ShimmerVariant` / `AnchorRule` / `SpawnDomain` / `GlowStyle` / `ShimmerVariants`，只注册 `water`；`CopperPanItem` → `PanItem` + `PanProfile`；地物参数化 + `ShimmerFeatureConfig`。**实体类暂不动**，`getVariant()` 临时返回 water 变体常量（P1b 再换成子类常量实现） | `./gradlew build`；自然生成、`/usb shimmer spawn`、淘洗产出、两视角动画与改前完全一致；旧存档正常加载。**这一步不碰实体注册、不碰渲染器清单、不动泛型**，因此不会触发 §3.2 的编译面 |
| **P1b 实体层次拆分**（已完成） | `ShimmerEntity` 抽象化 + `WaterShimmerEntity`；`ModEntities.SHIMMER` 按具体子类声明为 `Supplier<EntityType<WaterShimmerEntity>>`、`createShimmerType()` 换 `WaterShimmerEntity::new`；`ModEntityRenderers.RendererEntry` 与 `EntityRendererRegistrar` 改为接收 `EntityType<? extends T>`。此阶段仍只有 water 一个变体 | `./gradlew build`；重跑 P1a 的全部验证项，重点确认**客户端仍能正常渲染闪烁的光**（渲染器清单若漏行，表现是渲染时 NPE 而非启动失败）；旧存档实体正常加载 |
| **P2 账本与配置**（已完成） | `Entry` 加 `variant`（缺省 water）；自然生成上限改为按变体分别计数；清除与统计带变体过滤；新增 `getGoldPanLuckBonus` / `getObsidianPanLuckPenalty` / `getGoldPanRegenerationChance` 三项与两端实现、配置语言键（配置项在 P4/P6 才被消费，但统一在配置步骤落地） | 旧存档读入无异常；`stats` 输出新增变体标签，其余数值不变 |
| **P3 指令变体轴**（已完成） | `spawn <pos> [<变体>\|<来源>] [<来源>] [frozen]`（按变体铺该变体的代表介质）、`clear <变体\|<来源>\|all> [<来源>\|all] [范围]`、`stats [<变体>] [all]`、`chunk` 每条记录显示变体；`attempt` 仅水变体；`rate` 不需要变体轴。变体名由注册表路径名生成字面量，新增变体自动获得指令入口；第一位参数同时接受变体名与来源名，因此**原有 `spawn <坐标> <来源>` 与 `clear <来源>` 语法保持可用** | 各子指令输出正确 |
| **P4 金淘盘 + 幸运表** | `gold_pan` 物品（耐久 24、`c:ingots/gold`）；`luckByVariant = {water: +配置}`；再生走既有钩子并改为按变体找落点；`river.json` 加入三档幸运条目；tooltip、配方、tag、创造栏、语言键、帧模型与帧贴图（含 `atlases/blocks.json` 的 unstitch 条目，必须与贴图同批落地） | 把幸运加成临时调到 3 观察门槛系必出；调回 1.0 复验 |
| **P5 幽微的光** | `GlimmerEntity` + `ModEntities.GLIMMER` + `createGlimmerType()`（`GlimmerEntity::new`）+ **`ModEntityRenderers` 新增一行**；变体 `glimmer`（`LavaAnchor` / `NetherDomain` / 紫色 `GlowStyle` / `gameplay/panning/lava`）；`nether_shimmer` 地物与两端注入；客户端颜色、粒子、音效按变体取 | `/usb shimmer spawn <pos> glimmer worldgen`；**客户端能渲染幽微的光**；紫色波光；依附失效消散；把 `chance` 临时设为 1 验证新区块注入 |
| **P6 黑曜石淘盘** | `obsidian_pan` 物品（耐久 64、`c:obsidians/normal`）；`luckByVariant = {water: -配置}`；`DATA_PANNERS` 玩家集合同步 + `PanningMediumIndex`（含未刷新条目淘汰）+ `panning_medium` 属性；水/岩浆两套帧模型 | 两种点都能淘；第三人称看他人采幽微的光为岩浆帧；水点产出明显低于铜盘 |
| **P7 收尾** | 正式序列帧替换占位；更新 `docs/dev/` 四篇文档；最终构建 | `./gradlew build` |

P1 拆成 a/b 两步的理由：P1a 交付整个变体数据层与工具重构，只用极小的实体改动面，因此「等价」这个验收目标容易守住；P1b 再动实体层次时，变体数据层已经是验证过的已知量，出问题可以只在实体改动范围内排查。若实际推进中觉得拆开反而啰嗦，可以把 P1b 直接并入 P1a，但要接受「表现链路重接」与「实体类型注册」两类改动混在同一步里验收。

### 4.9 文件改动清单

**common 新增（Java）**

```
pan/variant/ShimmerVariant.java      pan/variant/ShimmerVariants.java
pan/variant/AnchorRule.java          pan/variant/SpawnDomain.java
pan/variant/GlowStyle.java           pan/variant/WaterAnchor.java
pan/variant/LavaAnchor.java          pan/variant/RiverDomain.java
pan/variant/NetherDomain.java        pan/ShimmerFeature.java
pan/ShimmerFeatureConfig.java        item/PanItem.java
item/PanProfile.java                 entity/WaterShimmerEntity.java
entity/GlimmerEntity.java            client/pan/PanningMediumIndex.java
```

**common 修改（Java）**

```
entity/ShimmerEntity.java            改为抽象基类
item/CopperPanItem.java              删除，并入 PanItem
item/ModItems.java                   新增 gold_pan / obsidian_pan 清单项与配置创建方法
entity/ModEntities.java              新增 glimmer；两处工厂改具体子类构造器，SHIMMER 按具体子类声明
pan/ShimmerPlacement.java            参数化，介质与群系由变体注入
pan/ShimmerSpawnService.java         按变体生成与清除；两处 level.getSeaLevel() 改为生成器取值
pan/ShimmerLedger.java               Entry 增加 variant 字段与变体过滤
pan/PanningLootService.java          按变体选表 + 叠加工具幸运 + 笔记上下文传变体表 id
command/ShimmerDebugCommand.java     变体轴（`spawn`/`clear`/`stats`/`chunk`，变体名与来源名共用首层字面量）
pan/variant/AnchorRule.java          新增 `mediumState()`，供指令铺设该变体的代表介质
pan/variant/ShimmerVariants.java     新增路径名查找与补全清单，供指令使用
platform/services/IPanningConfig.java 两个幸运配置项
client/renderer/ShimmerSurfaceRenderer.java   颜色与液面判定按变体
client/renderer/ModEntityRenderers.java       RendererEntry 改为接收 EntityType<? extends T> + 新增 glimmer 行
client/renderer/EntityRendererRegistrar.java  回调签名与两个平台 API 对齐为 EntityType<? extends T>
client/pan/PanningVisuals.java       为每把盘注册两个属性
client/pan/PanningAnimation.java     instanceof PanItem
client/pan/PanningSound.java         音效与偏移按变体
world/ModFeatures.java               地物清单与 placed key
```

**common 资源**

```
新增  data/unsuspiciousblock/loot_table/gameplay/panning/lava.json
新增  data/unsuspiciousblock/worldgen/configured_feature/nether_shimmer.json
新增  data/unsuspiciousblock/worldgen/placed_feature/nether_shimmer.json
新增  data/unsuspiciousblock/recipe/{gold_pan,obsidian_pan}.json
新增  assets/.../models/item/{gold_pan,obsidian_pan}.json
新增  帧模型 12 个：gold_pan_water_0..3、obsidian_pan_water_0..3、obsidian_pan_lava_0..3
新增  帧贴图 3 张：gold_pan_water.png、obsidian_pan_water.png、obsidian_pan_lava.png
改    data/.../loot_table/gameplay/panning/river.json        三档幸运条目
改    data/.../worldgen/configured_feature/river_shimmer.json  type 与 config 携带变体
改    data/minecraft/tags/item/enchantable/durability.json    加入两把新盘
改    assets/minecraft/atlases/blocks.json                    三组 unstitch 条目（必须与对应帧贴图同批落地，否则图集加载报错）
改    assets/unsuspiciousblock/lang/{zh_cn,en_us}.json        物品名、tooltip、配置 GUI、指令
```

**平台侧**

```
fabric    UnsuspiciousBlockFabric.java                       加一行下界群系注入
          platform/FabricPanningConfig.java                  两个幸运配置项
neoforge  data/.../neoforge/biome_modifier/add_nether_shimmer.json  新增
          platform/NeoForgePanningConfig.java                两个幸运配置项
```

**已就位资源**：`gold_pan.png`、`obsidian_pan.png` 已放入物品贴图目录，代码按此 id 接入。

### 4.10 文案约定

玩家可见文案不使用「提供额外幸运」这类技术化表述，改用暗示品质变化的说法：

| 键 | 简中 | 英文 |
|---|---|---|
| `item.unsuspiciousblock.gold_pan.tooltip.quality` | 更容易淘到稀有物 | Finds rarer things more often |
| `item.unsuspiciousblock.obsidian_pan.tooltip.quality` | 不适合淘洗水中沉积物 | Ill-suited to river sediment |
| `item.unsuspiciousblock.obsidian_pan.tooltip.use` | 对准闪烁的光或幽微的光长按右键淘洗 | — |
| `entity.unsuspiciousblock.glimmer` | 幽微的光 | Glimmer |
| `item.unsuspiciousblock.gold_pan` | 金淘盘 | Gold Pan |
| `item.unsuspiciousblock.obsidian_pan` | 黑曜石淘盘 | Obsidian Pan |

配置项仍使用技术命名 `gold_pan_luck_bonus`（默认 1.0）与 `obsidian_pan_luck_penalty`（默认 0.5），范围均 0~5，注释中写明是幸运值。

## 五、设计裁定记录

| # | 决策项 | 裁定 |
|---|---|---|
| 1 | 变体载体 | 两个 EntityType：`shimmer` 与 `glimmer`，共同继承抽象基类。**已裁定并已实施**，实际代价比原估更小：渲染器链需要改造（`ModEntityRenderers.RendererEntry` 与 `EntityRendererRegistrar` 改为 `EntityType<? extends T>`，与两个平台 API 对齐，平台侧零改动），但 **`ModEntities.SHIMMER` 的泛型不需要放宽**——按具体子类声明 `Supplier<EntityType<WaterShimmerEntity>>` 即可，原先判断的「协变合法但改法繁琐」不成立，真实障碍是隐式 lambda 的提前类型检查；两个工厂须换具体子类构造器；`defineId` 必须留在基类且子类不得覆写 `defineSynchedData`；P1 拆成 P1a（变体数据层，实体不动，已完成需验收）与 P1b（实体拆分，已完成）。**平行评估并否决**了「单 EntityType + 同步变体 id」：它在 P1 回归面与反向降级行为上更优，但会让幽微的光在数据包、选择器与外部工具眼里不是一个独立实体，与本计划「变体 = 注册项」的定位冲突 |
| 2 | 差异表达 | 数据注册表 + 两个算法接口；工具用基类 + 数据档案 |
| 3 | 产出表归属 | 由变体决定，不由工具决定 |
| 4 | 笔记归类 | 两表都自动归入「淘洗」，笔记侧零代码改动 |
| 5 | 金淘盘 | 采水点；再生走既有钩子；幸运正加成；概率取全局配置默认 0.10 |
| 6 | 黑曜石淘盘 | 采水点与幽微的光；无特殊效果；水点幸运负加成 |
| 7 | 配置 | 全局共用，仅新增两个幸运标量与一个再生概率 |
| 8 | 幽微的光生成 | 纯世界生成地物，`#minecraft:is_nether`，`VEGETAL_DECORATION`，`chance 100` |
| 9 | 依附判定 | 照搬水点：`Blocks.LAVA`（不区分流体等级）+ 上方空气 |
| 10 | 来源分类 | 完整保留三分类，只是不启用幽微的光的 natural 入口 |
| 11 | 介质同步 | 同步玩家 UUID 集合，远端玩家也正确 |
| 12 | 摇洗帧 | 按瞄准目标切换；只有黑曜石盘需要两套帧 |
| 13 | 紫光表现 | 波光紫 + 紫色粒子 + `BUCKET_FILL_LAVA` / `LAVA_AMBIENT` |
| 14 | 命名 | `gold_pan` 金淘盘、`obsidian_pan` 黑曜石淘盘、`glimmer` 幽微的光 |
| 15 | 配方 | 金盘用金锭与金粒；黑曜石盘用黑曜石与哭泣的黑曜石；均用 `c:` 标签 |
| 16 | 贴图 | 两把盘底图已就位，序列帧先用占位后替换 |
| 17 | 调试指令 | 完整变体轴；`rate` 除外 |
| 18 | 地物组织 | 参数化统一为一个 `ShimmerFeature` |
| 19 | 幸运来源 | 工具固定加成，金盘为正、黑曜石盘对水点为负、铜盘为零 |
| 20 | 表结构 | 共用 `river` 表 + 幸运门槛 |
| 21 | 占位表条目 | 门槛系与倾斜系并用，让负幸运可见 |
| 22 | 幸运可配置性 | 加配置项 + tooltip 暗示（不用「额外幸运」措辞） |

## 六、风险与限制

1. **实体类层次调整涉及面最广**。靠「基类保留 `ShimmerEntity` 之名」把 `instanceof` 位置的影响压到零，但 `ModEntities.SHIMMER` 泛型放宽会**在编译期**打断 `ModEntityRenderers` 的行（详见 §3.2），且该文件原先漏在 §4.9 清单外。验收上：P1b 必须确认「客户端仍能正常渲染闪烁的光」，P5 必须确认「客户端能渲染幽微的光」——漏登记渲染器的表现是渲染时 NPE 而非启动失败，只在实机看实体时才会暴露。
2. **Feature 类型注册名变更**：`configured_feature` 是数据包资源、每次加载重读，不破坏存档；但若外部数据包覆写过 `river_shimmer`，需自行更新。
3. **账本格式扩展**向后兼容（缺字段按水变体），但**反向降级**会丢失变体信息，未加载的幽微的光会被旧版当成水点。
4. **笔记收录依赖配置值**：`archaeology_path_prefixes` 是配置默认值，若旧存档持久化过不含该前缀的列表，新表不会被自动收录。落地后需实机确认。
5. **倾斜系条目会同时影响铜盘**（luck = 0 时按基础权重参与抽取）。当前 `river` 是占位表可接受；正式定表时若要保持铜盘手感不变，应把差异全部放进门槛系与额外池。
6. **幽微的光没有数量上限**，只靠地物 `chance` 控制密度，`100` 是初始值，需实机调整。
7. **负幸运的强度完全由倾斜系的 `quality` 幅度决定**，门槛系对负值没有额外惩罚；玩家自带负幸运（霉运效果）会与之叠乘。
8. **幽微的光与水平面共用同一套寿命与次数恢复参数**（配置全局共用）。若实测手感不合适，需要回到「按变体配置」重新讨论，那会扩大 `IPanningConfig` 的改动面。
9. **实施后需同步更新 `docs/dev/panning.md`**，它是现有机制的唯一权威文档，与本文在实施完成前会持续存在差异。
10. **`Level.getSeaLevel()` 硬编码 63**（详见 §3.4）。水变体不受影响（主世界生成器恰好也是 63），但任何按维度取海平面的新代码都必须走 `chunkGenerator().getSeaLevel()`，否则在下界会静默定位到 y = 64~60。这是一个「不报错、只是永远找不到落点」的失败模式，调试成本高。
11. **配置项不得在注册期求值**（详见 §4.3）。`luckByVariant` 若存成 `Double` 会在 NeoForge 上抛 `Cannot get config value before config is loaded`；Fabric 端因构造期直接读 JSON 而不会暴露，属于**只在单平台复现**的坑。
12. **`PanningMediumIndex` 需要一条显式淘汰规则**（本刻未被刷新的 UUID 即剔除），否则玩家停手后残留映射会让其他玩家长期显示错误的摇洗帧。同步字段本身是 §2.2 已声明的唯一例外，不再是待收口项。
13. **反向降级（新存档被旧版 mod 读取）在实体层面比账本层面更难看**。两 EntityType 方案下，旧版解析不了 `unsuspiciousblock:glimmer`，实体在区块加载时被静默跳过（仅留日志），而账本里对应的 worldgen 条目永远等不到实体来注销——worldgen 来源没有寿命，`expireDue` 清不掉它，会永久残留并污染 `stats` / `countWorldgen`。这是「变体 = 独立实体类型」的固有代价（被否决的单实体验体会按水处理并自愈），接受了本方案即接受了它。若实测中旧版回滚造成了可观测的账本残留，可考虑在账本加载阶段加一条「条目对应实体类型已不存在则清除」的兜底。
