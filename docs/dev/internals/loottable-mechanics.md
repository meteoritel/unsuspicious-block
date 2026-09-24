# 战利品表机制细节

> 战利品表的实现细则：四层解析管线、签名机制、概率模拟的场景规划与缓存、按需请求管线，以及展示值的派生规则。
> 本文件不参与任务导航，只被 [战利品表系统](../subsystems/loottable.md) 链接。**新增功能只需要读那篇**；只有修改解析/签名/模拟口径时才需要读本文件。

## 1. 解析管线细则

[`ArchaeologyJournalCatalog.load`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalCatalog.java)（位于 `journal/catalog/`）按四层管线构建目录：

```
capture 快照 → build 引用图 → 在追踪根初始可达集内算 SCC 排除集 → compile 闭包内每张表
             → project 每张表 → 组装静态读模型与分类结构 → 算每表哈希 → 原子发布
```

- **快照**（`LootTableSourceSnapshot.capture`）：一次 `listMatchingResourceStacks` 拿到全表。栈首是该表在本次重载下的**有效原文**（等价 `listMatchingResources` / `getResource`），整个栈用于哈希（等价 `getResourceStack`）；原文与 `JsonElement` 惰性读取并缓存。
- **图**（`LootTableReferenceGraph.build`）：有效 JSON 引用边 + 平台注入合成边。合成边施加**双端存在守卫**，目标已由 JSON 引用覆盖时不重复加边；邻接表按目标去重但保序。
- **环排除**：只对追踪根的初始可达集计算强连通分量，其成员被排除出收录闭包并输出一次性告警；与考古目录无关的第三方表循环不新增告警。
- **编译**（`LootTableCompiler`）：单表 JSON → 上下文无关的 `CompiledLootTable`。遇到 `loot_table` 引用只记 `ReferenceSite` 不跟随；`item` / `tag` 记物品路径，其中 **item tag 在编译期展开为具体物品**（签名按具体物品生成，存档缓存也按具体签名索引）。
- **投影**（`LootTableProjector`）：沿 JSON 引用把编译产物链接起来，复现三条语义——首跳子表归属、条件继承顺序（上层传入 → 本表内已继承 → 本事件自身）、函数继承与 `APPROX_ITEM_ONLY` 降级。引用位置按出现顺序逐个进入，**不去重也不合并**：同一子表在两处被引用且条件不同时两条获取路径都要保留。
- **读模型**：`StaticTableProjection` 展平出的 `TableDefinition` 只收真正产出物品的表（会话保留全部追踪表的投影，含空表）；无物品的表既不进目录也不作为子表入口。此阶段概率为 `Probability.unknown(UnknownReason.UNCOVERED)` 占位，等待模拟填充。

**编译与投影的区别是刻意的**：同一个子表在不同引用位置产出的物品路径不同（条件与函数被引用位置改写），因此编译产物只记"本表直接物品路径 + 引用位置"，**绝不缓存展开后的子表结果**。

`LootConditionHandler` / `LootFunctionHandler` 把原版条件/函数转译为可读的 `LootConditionInfo`；条件分析、函数链拼接与多路径合并各只有一份实现（`LootParseUtil`、`ItemDefinitionAccumulator`），保证编译路径与投影路径产出逐位一致的条件指纹与签名。

### 1.1 多路径物品提示的合并

`ItemDefinitionAccumulator` 对同签名条目的非空提示按 `Component` 结构（包括翻译键与参数）去重，按路径首次出现顺序保留，用本地化的 `alternative_separator` 拼接；**不使用已翻译文本判断相等**。展示名的分歧判断也使用组件结构。空提示不抹去其他路径的已知提示。

各路径区间相同时只出现一次——内置河流淘洗的金粒两条路径同为 `数量: 1-3`，因此不出现分隔符；区间不同时按路径出现顺序拼接，形如 `数量: 1-3 / 数量: 3-6`。这些是各路径的函数效果，**并非整张表一次抽取的总产量**，也不表示各路径一定同时触发。`uniform` 两端相等时显示 `数量: N`。

## 2. 签名机制

[`LootResultSignature`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/signature/LootResultSignature.java) 是进度匹配与持久化的核心：它让"目录中的物品"与"玩家实际获得的物品"能对应起来。

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

### 2.1 实例态组件排除

签名派生时，若物品的组件补丁含配置声明的**实例态组件**（`ILootTableConfig.getSignatureExcludedComponents()`，默认 `relics:data`），该物品直接退化为 `PLAIN`（物品级）签名。这类组件随物品实例随机化或随玩家进度变化，不属于战利品定义。

以 Relics 为例：`relics:data` 是所有 `IRelicItem` 的**默认组件**（初值 `RelicComponent.EMPTY`，即未解析态），而该模组在 `Item.verifyComponentsAfterLoad` 里"读取即物化"地用未播种随机数写回品质，`ItemStack` 构造、`copy()`、`applyComponents*` 都会触发它——把这种组件编进签名等于把 RNG 结果当物品身份，同一件饰品会随掉落次数无限增生条目（实测冰窖表 1802 条里 1796 条是两件饰品的伪变体，使该表完成度不可达）。

**必须落 `PLAIN`，而不是"剔除该组件后仍编 `COMPONENT_EXACT`"**：匹配比较（`isSameItemSameComponents`）与候选索引哈希（`hashItemAndComponents`）读的都是**未剔除**的组件，两侧不对称会让这类条目永远匹配不上、索引也永远查不到桶。`PLAIN` 匹配只比物品 id，不碰组件，且与静态路径产生的 `PLAIN` 共用同一个存储键，因此同一物品不会出现两条条目。代价是不再按随机变体分别收集——与附魔折叠为 `ENCHANTED_APPROX`（"避免签名爆炸"）是同一取舍。

### 2.2 稳定存储键

`toStoredKey()` 生成稳定字符串，用于 NBT 持久化、网络传输与 Map key：

```
usb_sig|TYPE|itemId|base64(data)
```

`fromStoredKey()` 反向解析，并**兼容旧版**：不以 `usb_sig|` 开头的 key 视为旧版仅存 item id 的格式，转为 `PLAIN` 签名。这保证玩家旧存档不丢失。

### 2.3 附魔判定的陷阱

`isActuallyEnchanted(stack)` **不能用 `stack.has(ENCHANTMENTS)`**，因为工具/武器默认带有**空的** `ENCHANTMENTS` 组件，`has()` 会误返回 true。正确做法是检查 `stack.isEnchanted()` 或 `STORED_ENCHANTMENTS` 非空（附魔书场景）。

### 2.4 预览栈

`createPreviewStack()` 根据签名重建用于 UI 展示或精确匹配的物品栈：`COMPONENT_EXACT` 应用组件 patch，附魔变体设置附魔光效覆盖。

`COMPONENT_EXACT` 的预览栈需要把签名的 `data` 当 JSON 解码再套用组件补丁（**`data` 本身不是 base64**，base64 只出现在 `toStoredKey` 的存储键里），成本远高于其它类型的匹配。因此 [`LootResultPreviewCache`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/signature/LootResultPreviewCache.java) 按签名内容缓存预览栈——预览栈由签名值唯一决定，**缓存结果永远有效、不需要失效策略**；玩家侧的掉落实时匹配（掉落追踪、容器已追踪状态、菜单快照三条来源）都通过 `LootResultPreviewCache.PROVIDER` 取用，避免每次掉落、每个槽位重复解码。缓存只在数据包重载时清空以限制内存。

概率模拟热路径**不共用它**：单表模拟自带 `HashMap` 缓存，单线程且无并发开销，更快。含实例态组件的物品已退化为 `PLAIN`，其预览栈是裸物品栈、不套用任何组件补丁，也不再需要预览参与精确匹配。

## 3. 概率模拟：引擎与场景策略

模拟的输入身份是 `SimulationInput` = **场景身份** + 条件布尔赋值 + `ScenarioParams`（幸运 / 工具基座 / 工具附魔等级 / 抽样次数）。缓存键是 `(表哈希, SimulationInputKey)`，因此"同一张表换个幸运值"是新增一条缓存，而不是覆盖旧的一条。

**场景身份是稳定字面量，不编码条件指纹**（这是缓存能否命中的关键）。基准场景恒为 `baseline`，其余为 `scene-1`、`scene-2`…（序号＝规划器的发射顺序）。条件指纹**仍然是场景的运行时语义**（`SimulationProfile.conditionOutcomes`，模拟时按运行时指纹查表回答条件成立与否），但它**不再进键**：指纹由条件对象的 `toString()` 派生，`entity_properties` / `location_check` 这类未按字段值生成文本的类型会退化成 `类名@identityHash`，跨 JVM 运行不重复，于是同一个场景每次启动都会换一个新键——读路径永远找不到旧的，写路径又不断新增，而 LRU 按参数组合计数，这些孤儿键既不会被淘汰也不会被覆盖，在存档里无限累积。缓存键的形态因此是：

```text
scenario=baseline|luck=0.00|tool=minecraft:diamond_pickaxe|ench=|n=10000
```

### 3.1 模拟引擎

[`LootProbabilitySimulator`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulator.java) 对**单个输入**执行模拟抽取：

```
simulateOne/createJob(tableId, rawTable, level, input, scenario)
  ├─ 从 ReloadableServerRegistries 取得数据包重载后的运行时 LootTable
  │    保留 Fabric LootTableEvents.MODIFY 等加载期注入；不从原始 JSON 重建表
  ├─ 合成 profile = 场景条件赋值 + 输入参数（ScenarioParams.applyTo）
  │    SimulationProfile 提供 TOOL、BLOCK_STATE、DAMAGE_SOURCE、ORIGIN 等值
  │    钓鱼上下文（由表**声明的 type** 判定，不看表 id 的 path）的 THIS_ENTITY 用
  │    SimulationFishingHook 填充，使 entity_properties + fishing_hook + in_open_water
  │    条件在模拟中可判定。
  │    填充规则：只填该 paramSet `allowed` 内的参数，required 与 optional 都填——
  │    只填 required 会让 optional 参数缺失，把"没填"伪装成"条件不成立"。
  │    paramSet 不允许 ORIGIN（如 barter）时明确失败，这类表在构建期就被拦下，不走这里
  ├─ 初始化该场景适用的候选签名 + appearanceCounts
  └─ 循环 input.params().sampleCount() 次：
       ├─ lootTable.getRandomItems(lootParams)
       ├─ ArchaeologyLootInjectors.get().maybeReplace(tableId, drops, random)
       │    Fabric 显式调用注入器；NeoForge 空实现（GLM 已在 getRandomItems 内部完成）
       ├─ 对每个 drop：
       │    ├─ LootResultMatcher.resolve(stack, candidates) 匹配已知签名
       │    └─ 未匹配 -> deriveSignature 派生签名（附魔折叠为近似，其他组件严格保留）
       │         若已是已知签名 -> 保守跳过（歧义掉落）
       │         否则登记为注入条目（injected=true）
       └─ 对本轮出现的签名去重后计数
```

物品概率口径是"单次战利品表抽取中，该签名至少出现一次的概率"。同一轮返回多个相同签名的 `ItemStack` 时只计一次，**不统计物品数量或平均产量**。

模拟输出的是原始测量值 `SimulationMeasurement`：按签名记录测量值、按直接子表记录"至少产出一个物品"的测量值，外加模拟期动态发现的签名与其来源。**展示值由服务端当场派生**，因此"从缓存恢复"与"刚刚算完"走同一条代码，不存在两套口径。

幸运由 `LootParams.withLuck` 注入，取 `input.params().luck()`；基准输入的幸运为 0。需要更高幸运阈值的条目会如实落到「需要条件」，而不是被伪造成一个数字。

编译器在每个 pool 检查 `bonus_rolls` 与所有候选（含组合 entry 子节点）的 `quality`：非零质量会改变同池权重竞争，因此整个池标记为幸运敏感；无法证明恒零的奖励抽取 provider 也保守标记。该标记随引用位置向子表传递，再写入 `LootAcquisitionPath.luckAffected`，独立于真实条件树、函数等级及物品签名。物品和父表中的子表入口 tooltip 显示"随幸运变化"，**不把它误写成"必须幸运才能获得"**。此静态标记覆盖 JSON 的标准幸运机制；第三方运行时注入或自定义条件/函数暗中读取幸运时无法保证识别。

模拟必须调用普通 `LootTable.getRandomItems`：NeoForge 会在原始抽取结束后由该入口应用 GLM，而 `getRandomItemsRaw` 不会应用 GLM。嵌套表由原版 resolver 从同一个运行时注册表解析，且嵌套抽取使用 `getRandomItemsRaw`，因此 GLM 只在根表应用一次。

**热路径注意事项**（改动匹配逻辑前必读）：模拟热路径先按 `Item` 分组，再由 `LootResultMatcher.CandidateIndex` 对精确组件候选按 `ItemStack.hashItemAndComponents` 分桶。索引用**原签名解码并校验后的缓存预览**构建，查询用真实产物的同一哈希；同桶内仍逐个执行 `ItemStack.isSameItemAndComponents`，**不把哈希相等当作匹配**。普通/附魔近似/物品近似候选另存一个小列表，与命中的精确桶共用最高优先级和歧义状态；精确候选有歧义时仍返回 null，不错误回退。候选在动态发现时增量加入，场景结束后释放当前场景索引。哈希碰撞只增加同桶扫描成本，不改变结果；提供者的缓存预览在索引存活期间不得修改。匹配使用局部状态，不为每次匹配创建排序 Map 或临时 List；每个候选缓存 stable key，并由带轮次标记的原始计数器完成单轮去重。上述优化不改变签名优先级、歧义回退或"每轮最多计一次"的概率口径。

### 3.2 条件场景策略

运行时表保留全部条件，**不能再通过读取原始 JSON、删除条件并重新解码来生成模拟副本**。那种做法会绕过 Fabric 加载期修改，也无法可靠表达 `all_of`、`any_of`、`inverted` 等组合条件的语义。

`SimulationScenarioPlanner` 从每个 `LootAcquisitionPath` 提取**七类**资格条件（`SCENARIO_CONDITIONS`）为每条可达路径建立最小布尔赋值场景，并为每个场景算出静态可达的签名集合与直接子表集合。**其中一个场景被标记为基准场景**（条件全部不成立的那个），网格只读它——取跨场景最大值等于把"你站在沼泽里"那个数当成玩家的处境展示。解析阶段把 `simulation_fingerprint` 写入每个条件的 metadata；运行时 Mixin 用同一指纹查询 `SimulationProfile` 中的精确 true/false 结果，因此同类型的两个 `location_check` 不会混淆。

| 条件 | 当前处理方式 |
|---|---|
| `match_tool` | **已移出场景控制类型**：工具由 profile 填充的真实 `TOOL` 求值，能否匹配交给 `ItemPredicate` 自己回答。此前它被伪造成布尔，于是"工具匹配"与"时运等级"互不相干——条件说匹配成功，而读真实 `TOOL` 的 `apply_bonus` / `table_bonus` 拿到的是无附魔镐，基础场景的时运曲线恒为 0 级。引用它的路径在基准（无附魔钻石镐 / 钓竿）下落到「需要条件」并指名"工具"旋钮 |
| `block_state_property` | profile 填充 `BLOCK_STATE`；条件门槛按匹配场景处理 |
| `damage_source_properties` | profile 填充 `DamageSource`；复杂伤害谓词按匹配场景处理 |
| `entity_properties` | 填充假玩家；钓鱼表的 THIS_ENTITY 使用 `SimulationFishingHook` |
| `location_check` | 场景按具体条件指纹分别取 true/false，不查找或生成真实区块 |
| `weather_check`、`time_check` | 场景按具体条件取值，不修改服务器天气或时间 |
| `entity_scores` | 场景按具体条件取值，不创建 objective、不写真实 scoreboard |

**场景数量的两条独立约束**：

- **硬上界 32**：候选按"覆盖的获取路径数"降序截断（覆盖更多条目的场景优先留下），被丢弃的数量如实上报（`ScenarioPlan.truncatedCount`），构建时逐表打进日志。**基准场景先占名额**——它由全假赋值产生、覆盖度天然最低，排序后会第一个被丢掉。
- **展开预算**：`requirementsFor` 对 `all_of` 走叉乘、对 `any_of` 累加，因此"`all_of` 里嵌多个 `any_of`"的条件树会在 32 的截断**之前**就指数膨胀——上界约束的是展开的*结果数量*，约束不了展开的*过程成本*。因此节点数与中间组合集合各有一个预算，超限后该表**整体**按"无约束"降级并告警一次。降级方向是保守的：条目保留在所有场景中，数值可能偏乐观，但不会把可达条目伪装成不可达。

每条路径的需求集合只计算一次，供"候选收集 / 覆盖度排序 / 适用性判定"三处共用。

**发射顺序必须可复现，因为它同时决定场景键**。场景键是缓存键的一段，顺序一变键就变。两条约束：候选集合按**路径枚举顺序**插入（来自解析后的 JSON，与运行无关）；覆盖度排序**只按覆盖路径数降序**，并列时保持插入顺序（Java 排序是稳定的），**不按指纹字符串破并列**——指纹跨运行不稳定，拿它当二级键会让并列候选每次启动互换序号，带并列场景的表（例如原版 `minecraft:gameplay/fishing` 的群系条目，每条恰好覆盖一条路径）因此每次启动都全量重算。

**指纹稳定性判定**：指纹取自条件对象的 `toString()`，因此**只对按字段值生成文本的类型成立**（record 即是）。解析阶段同时写入 `simulation_fingerprint_stable`：识别出 `类名@identityHash` 这类默认实现时，该条件不参与场景规划（按无约束处理，条目因此保留在各场景中而非被判成不可达）并告警一次——否则解析期与运行时的两个实例必然算出不同指纹，场景覆盖会静默失效。运行时另有兜底：属于场景控制类型、却没有被任何代表场景覆盖的条件，会在该表模拟完成时汇总告警，提示这部分数值是按真实逻辑求值得到的。

这个稳定性判定**只检查条件对象自身，看不进嵌套字段**（已知缺口）。三条已核实的事实：

1. 解析期分析的是 `LootTableCompiler` 从 JSON 解出的**另一份实例**，与运行时 `ReloadableServerRegistries` 里的对象不同，因此只有**整条** `toString` 链都是按值生成的类型才能复现指纹；
2. `isStableSource` 只问"最外层是不是 record / 是不是 `类名@identityHash`"，`LootItemEntityPropertyCondition`、`EntityPredicate`、`FishingHookPredicate`、`LocationPredicate` 都是 record，一律被判定为稳定，嵌套的非 record 对象不参与判定；
3. `location_check` 带标签时必然不稳定：`LocationPredicate.biomes` 是 `Optional<HolderSet<Biome>>`，标签集 `HolderSet.Named` 的 `toString` 会逐个打印 `Holder.Reference`，而后者打印的是**它持有的值对象**（`"Reference{" + key + "=" + value + "}"`）——`Biome` 没有按值生成 `toString`，于是整条链落到 `类名@identityHash`。存档里这些表的键每次启动都不同，正与此一致。

后果是：这类条件在运行时查不到对应指纹，按**真实逻辑**求值并计入"未覆盖"汇总告警，数值口径因此与场景估算不同。告警本身按 `条件 id + (简单类名)` 去重，所以"有 N 个"报的是**类型数**而不是条件条数，不能读成"只有 N 条条件未覆盖"。

**该告警的常见来源不止一处**：追加的泥地打捞注入场景有意只带 `location_check` 的类型默认值、**不带**基础场景的条件指纹表，好让注入路径上的 `entity_properties`（`in_open_water`）交给真实逻辑（假浮标 `SimulationFishingHook`）判定——若把基础场景的指纹表传进去，"默认"场景会把 `in_open_water` 归一到 `false`，反而把宝箱与泥地打捞的条目强制判成不可达；同时，基础场景里**任何**指纹不稳的条件（如带标签的 `location_check`）也会计入同一条告警。它只在该表**实际发生模拟**时输出（缓存命中时不会出现），无需处理。

**注入场景的自带工具不被参数覆盖**：原版 fishing 的泥地打捞注入场景把"满级钓竿"写进了场景定义本身（`SimulationScenario.keepBaseTool = true`），因为那条场景的全部意义就是带着能通过 `tool_enchantment` 门槛的工具去抽注入池。若用输入的默认工具覆盖它，注入池永远抽空、注入条目再也发现不了——那是信息丢失，不是"参数生效"。因此这类场景只接受输入的幸运，工具保持场景自带的设定。

`LootSimulationScope` 通过 `ThreadLocal` 仅在当前主线程该输入的全部抽取期间暴露 profile，并由 try-with-resources 确保异常时清理。窄 Mixin 只把场景控制叶条件的 `test` 转交作用域；`all_of` / `any_of` / `inverted` 始终由原版逻辑根据叶子结果求值，随机条件、权重和 rolls 不覆盖。

### 3.3 逐路径幸运门槛

[`LuckGateAnalysis`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LuckGateAnalysis.java) 从编译期记下的 [`LuckSpec`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LuckSpec.java) 推出每条路径的最小幸运门槛，结果按**路径分别保留**（`LootAcquisitionPath.luckGate`）——取多路径的最小值会得到一个无法指回是哪条路径需要它的数字。tooltip 因此**逐条目、逐路径**各自显示自己的门槛（同一物品的不同路径门槛不同时两条都会出现），不合并成一条全表通用的说明。

判定读原版的两条真实公式（1.21.1）：权重竞争 `max(floor(weight + quality × luck), 0)`（只有 ≥ 1 才进入抽取），奖励抽取 `rolls + floor(bonus_rolls × luck)`（额外抽取只在 `floor(bonus_rolls × luck) ≥ 1` 时出现）。**没有任何原版战利品条件直接读幸运**，因此这两条就是"幸运能不能改变这条路径"的全部入口。

算法分两步：先由代数解出候选门槛，**向上对齐到 0.01 网格**，再用上面两条真实公式**回验**；回验不通过就按 0.01 步进直到通过或触及预算。这样给出的数值是"照着填就能生效"的值（`1/3` 的上对齐结果是 `0.34`，且 `floor(3 × 0.34) = 1` 确实成立），而不是填不进去的"约 1/3"。

降级原则：满足集合不是"从某个数往上全满足"时（负 quality 造成的上限、代数解落在可表示范围之外、或步进预算耗尽），一律**不给数值**并标记为"区间受限"——给一个数就等于承诺"高过它就能拿到"。同理，无法静态求值的 `bonus_rolls` 记为"未知"而不是零。工具不参与门槛：它由 `ItemPredicate` 单独判定。

`LuckSpec` 的采集有两条易错点，都在编译期处理：

- `bonus_rolls` **缺省**在原版是 `constant 0`（**不是**"未知"），而字段存在但值是动态提供器时必须保留为"未知"；
- `group` / `sequence` 的子节点**不参与权重选择**（只有 `alternatives` 才按权重挑一个），因此那里的 `weight`/`quality` 按原版缺省处理——照抄数值会把"组内必然产出"的条目误判为需要幸运或不可达。

### 3.4 静态信息性提示的组成规则

`PathHintAnalyzer.hintsFor` 把一个条目的获取路径翻译成"需要条件"的逐行陈述。两条易错的组成规则：

- **只有组合条件（`all_of` / `any_of` / `inverted`）的子节点是"另一条条件"**。其它类型的 `LootConditionInfo.children()` 是**同一条条件的展示子行**（例：`tool_enchantment` 的"概率：基础 20%，每级变化 10%"子行）。早期实现无条件递归，于是同一条门槛被列两遍，第二遍还把概率子行写成"该路径需要工具带 概率：… 附魔"这种读不通的句子。子行里的数值（等级门槛、按等级概率）仍由条件树原样展示，信息不丢。
- **附魔提示不写死等级门槛**。原文案硬编码"（等级 ≥ 1）"，但 `min_level` 由数据包给出，写死就会在 `min_level > 1` 的表上说谎。提示只陈述"需要工具带 X 附魔"（"带有该附魔"本身就是该条件类型的固有语义），具体等级以条件树的子行为准。

未解析条件另用 `PathHint.UnresolvedConditions` 表达，与可调旋钮和场景条件分开；组合条件递归收集其未解析叶子，避免把组合父行和叶子重复列出。该提示由 `CatalogStreamCodec` 下发，并参与目录摘要。

幸运提示优先给具体数值：能算出门槛就写"该路径需要：幸运 ≥ 0.34"（玩家可直接照填），只有 `luckAffected` 而无从计算时才退回"该路径需要幸运加成"这句无具体目标的陈述。

tooltip 里同时给出其它代表场景的最小/最大值供对照（网格给的是当前输入的值，两者口径不同，不互相冒充）。代表场景用于控制组合数量与 UI 长度，因此范围**不是所有现实条件组合的严格数学上下界**。同一场景内多条路径产出同一签名时仍由整表模拟自然合并。

### 3.5 嵌套子表观测

嵌套子表由 `NestedLootTableMixin` 在模拟作用域中直接观测。每次父表抽取内按子表 ID 去重，统计"该直接子表至少产出一个物品"的概率；**不通过物品签名反推**，因此父子表产物重叠不会造成误判。作用域只保留根表的直接子表，使用复用 List 记录本轮命中，并通过 `IdentityHashMap` 关联产物来源；身份未保留时再按物品与组件相等回退。内部命中集合由同步回调直接遍历，不为每轮创建副本。父表 UI 不再平铺 `sourceChildTable != null` 的物品路径，而是显示可点击的子表入口及该概率。

平台运行时注入的 `minecraft:gameplay/fishing -> unsuspiciousblock:gameplay/fishing/mud_dredging` 关系由公共目录加载器补入引用图；NeoForge GLM 直接调用子表时也显式写入同一模拟观测作用域。

原版 fishing JSON 看不到 Fabric 加载期注入池或 NeoForge GLM。规划器因此额外建立普通群系与加成群系两个代表场景（使用泥地打捞满级工具，等级取自附魔自身的 `max_level`，且 `keepBaseTool = true` 使其不被参数覆盖），**只用于让运行时注入的条目仍被模拟发现**。这两个场景不是基准场景——启动只跑基准输入（无附魔钓竿），因此注入条目在**被请求之前**显示为「未覆盖」而不是某个"最有利组合"的数字。

**父表页的子表入口按这条门槛报「需要条件」**：入口被门槛挡住时，报"抽样零命中"是错误归因——它把确定的原因说成运气。因此子表入口的展示值走与物品同构的派生（`PathHintAnalyzer.deriveEntryDisplay`：零命中 + 有可陈述门槛 → `NeedsCondition`），条件树由服务端下发（路径共同成立的条件 ∩ + 注入门槛），客户端不再从物品路径本地重推——注入边不写在任何 JSON 里，本地重推必然漏掉它（历史症状：泥底打捞入口显示成没有原因的「未命中」）。调好条件后（例如带满级附魔的注入场景）该入口显示的就是那个场景测到的数值，不需要另一套逻辑。

## 4. 概率模拟：主线程 tick 驱动

[`LootProbabilitySimulationWorker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulationWorker.java) 是模拟的调度器。**关键约束**：`LootTable.getRandomItems` 与 `LootContextParamFiller` 会触碰 `ServerLevel` 关联的 `LegacyRandomSource`，后者不是线程安全的，后台线程与主线程 tick 并发会触发 `ThreadingDetector` 报错。因此**不用后台线程**，改为：

- 在服务端每 tick 末尾（`END_SERVER_TICK`）由 `tick(server)` 消费队列。
- 任务由 `LootProbabilitySimulationJob` 保存抽取次数、签名与计数，可跨 tick 续跑；一个任务只跑**一个输入**。
- 每 tick 使用 15ms 软预算，每 32 次抽取检查一次时间；同一输入的模拟作用域在当前 tick 时间片内复用，避免每批重建观测容器。该预算优先保证服务端启动阶段尽快完成模拟，单次抽取无法中断，因此极端复杂的单次抽取仍可能略超预算。
- 高优先级请求会把已在低队列中的**同一输入**提升到高队列；不同的高优先级任务会在下个 tick 边界抢占当前低优先级任务，且不丢失进度。
- **双优先级队列**：`highQueue`（玩家按需请求与解锁插队）先于 `lowQueue`（启动基准填充）。
- **去重键是 `(表, 输入键)` 而不是表**：抽样次数与参数都进了输入身份，同一张表的两个参数组合是两个不同的问题，按表去重会让后一个请求把前一个顶掉。
- **限流**：HIGH 队列待处理上界 32、每玩家在途上界 2。两条都**只约束玩家触发**的部分——启动批量填充由服务端自己排定、数量等于收录表数，把上界也套在它身上只会让"表很多"被误判成"被刷爆"。玩家解锁触发的插队（`enqueuePriority`）同样不计入玩家额度：解锁是游戏进程自然发生、每表至多一次的事件。
- **同一个输入的多个等待者都会收到结果**：队列里已有 `(表, 输入)` 时不重复排一次，而是把新请求者登记为等待者，完成时逐个回执——否则"启动批量正在算的正好是我要的那个输入"会让玩家点了却永远收不到结果。
- 数据包重载期间 `pauseForReload()` / `resumeAfterReload()` 暂停消费。
- 队列排空后触发 `queueDrainedHandler`（批量广播目录哈希）。
- `isBusy()` 同时检查 reload 暂停、当前任务和待处理队列；需要读取完整动态物品集合的命令据此拒绝半成品目录。

**完成日志输出三个时间，用途各不相同**：**线程 CPU 时间**回答"这张表本身有多贵"；**有效计算耗时**是累计在 `advance` 内的墙钟时间，在服务端启动阶段会因与区块生成、视距调整等工作争抢 CPU 而明显虚高；**跨 tick 历时**还包含任务在 tick 之间等待的时间。判断某张表是否变慢要看 CPU 时间，不要直接比"有效计算耗时"。日志同时报出该输入的场景键与参数。

### 4.1 可选性能观测与日志解析

`LootSimulationMetrics` 默认关闭；当前归因阶段的 NeoForge 开发 `runClient` 已在 Gradle 配置中开启，IDEA 的 Gradle 面板启动同样生效，发布 mod 不受影响。其他启动方式以环境变量 `USB_LOOT_PROFILE=true` 启动游戏，或在**游戏 JVM** 设置 `-Dusb.loot.profile=true` 后，完成日志保留原有格式并追加 `profile=v1` 和以下字段。观测器只在 `advance` 时间片内绑定当前线程，正常返回、提前让出与异常路径都会恢复绑定。它不修改 15ms 预算、32 次检查间隔或随机源；开启时的时钟和计数开销仍会占用真实时间，所以可能增加跨 tick 切片数，**不能宣称零性能开销**。关闭时不读取分段时钟、不查线程绑定、不追加字段。

| 字段 | 口径 |
|---|---|
| `SCENARIOS` / `ROLLS` | 实际准备的场景数 / 根表 `getRandomItems` 调用数，含全部场景 |
| `DROPS` | 显式注入处理后送入匹配的非空 `ItemStack` 数，不是栈内物品数量 |
| `SCANS` | `resolve` 实际扫描的候选总数，包含当前场景及原始候选的回退扫描 |
| `EXACT_SERIALIZATIONS` | `encodeComponentPatch` 的实际编码次数；空补丁及缓存命中不计 |
| `STORED_KEY_CALLS` | 时间片内实际进入 `toStoredKey` 的次数，含准备与结果组装；存储键缓存命中不计 |
| `PREVIEW_BUILDS` | 任务内预览缓存未命中的构造次数 |
| `MATCHED` / `RAW_SKIPPED` / `DERIVED` | 三个掉落处理分支，成功任务中三者之和必须等于 `DROPS` |
| `CANDIDATES_ADDED` | 各场景实际新增候选数，包含静态初始候选及动态发现 |

分段的 `_ns` 字段均为 **`System.nanoTime` 累计墙钟纳秒，不是 CPU 时间**：

- `PREPARE` / `FINISH` / `RESULT`：上下文和候选准备 / 场景收尾 / 最终结果组装。
- `BEGIN_ROLL` / `GENERATE` / `INJECT`：清理单轮作用域 / `getRandomItems` / 显式注入。`GENERATE` 包含 vanilla、现有 Mixin 与 **NeoForge GLM**；不能直接称为"vanilla CPU"。
- `MATCH` / `RAW_MATCH`：当前候选匹配 / 原始候选回退，包含候选查找和预览缓存查询。
- `DERIVE` / `KEY_LOOKUP` / `RECORD` / `CHILD_RECORD`：派生签名 / 查询存储键缓存 / 更新计数、发现来源和增量候选索引 / 子表计数。精确候选首次加入索引时构造缓存预览，所以其 `PREVIEW_DETAIL` 会落在候选准备或 `RECORD` 内；原始索引构建计入任务创建。
- `SERIALIZE_DETAIL` / `STORED_KEY_DETAIL` / `PREVIEW_DETAIL` 是**嵌套明细，已包含在父段中，不得再次相加**。未分配时间还包括任务创建/场景规划、循环、观测本身及时间片作用域维护。

使用 [`scripts/loot_simulation_profile.py`](../../../scripts/loot_simulation_profile.py) 解析明确的 `/usb journal reload` 标记后的完整轮次，拒绝启动轮、未结束轮及重复表；用同一脚本导出基线和后续数据。原始热运行证据与操作说明保存在本地目录 `docs/archive/loot-performance/`——该目录含本机路径、存档摘要与原始日志，**不入库**（见 `.gitignore`），因此这里只写路径、不做链接。小任务的线程 CPU 可能因平台计时分辨率显示 0ms，不能据此断言零成本。最终性能收益需在相同观测模式、相同抽样次数和表集合、完成预热的条件下复测。

## 5. 概率模拟：结果缓存与失效

模拟结果通过 `LootProbabilityData`（SavedData，附加在 overworld）持久化。测量值按**输入键**保存（`inputKey -> {该输入的每签名测量值, 每直接子表测量值}`）；**落盘只用窄类型 `SimulatedValue`（`Unknown` / `Measured`），并只持久化"该输入测到了多少"**——不可用场景的「需要条件」恢复时由规划出的场景与路径静态结构重新派生，因此改了静态分析规则不需要迁移存档。嵌套引用的获取路径在每个根表视角下保留第一层子表来源，使孙表条件导致的不可达场景能够汇总到直接子表。动态条目的直接来源标记（`hasDirectSource`）与子表来源列表（`sourceChildTables`）会一并持久化到父表缓存，恢复时优先使用缓存的来源信息，仅在其缺失时才用直接子表及其后代缓存中的相同签名重建获取路径。

**存档结构分三层**（`format_version = 4`；P1 之前的 3 是"扁平 items + 每签名分场景值"，版本不符即整体按缓存未命中处理，不做迁移）：

| 层 | 内容 | 淘汰 |
|---|---|---|
| `hash` | 该表的内容哈希（SHA-256） | 变化即整表作废 |
| `discovery` | 表级发现记录：动态发现签名的"是否直接产出 / 来源直接子表" | **LRU 不淘汰** |
| `inputs` | `inputKey → { 抽样次数, 每签名测量值, 每直接子表测量值 }` | 按参数组合 LRU |

- **发现记录挂表级、LRU 不淘汰**：动态条目（GLM / `LootTableEvents.MODIFY` 注入）没有静态路径，它的"直接来源 / 来源子表"只能从模拟观测里得到。若把它放进会被淘汰的测量值里，淘汰一次就会让这批条目在重启后**从网格里消失**——那是信息丢失，不是缓存失效。
- **测量值按输入键索引**：键是 `(表哈希, SimulationInputKey)`，于是"同一张表换个幸运值"是新增一条，而不是覆盖旧的一条。
- **LRU 按参数组合计数**：每个参数组合（**不含抽样次数**）最多保留全部次数档位，参数组合数上限 8，实际条目 ≤ 8 × 档位数。理由是换参数是换**问题**，换次数是换**答案的精度**——高精度答案不该把问题本身挤出缓存。访问顺序在内存中按"最近使用"更新（`LinkedHashMap` 访问顺序），落盘沿用上一次写盘时的顺序；重启后精度可能回退到上次写盘的状态，代价只是"换一个组合被淘汰"，不影响正确性。
- 存档根带 `format_version`（当前 4）。读取时先校验版本与**严格的 NBT tag 类型**——根缺少版本、版本不符、概率仍是旧版 `StringTag`（注意这并**不是**"字段缺失"，必须按类型显式判定），或单个表的条目无法解析时，一律把对应表按**缓存未命中**处理：既不报错中断，也不迁移数值、更不把类型不匹配解成 0。
- 每表还存一份内容哈希（SHA-256），输入覆盖**整棵子树**：每张表的完整资源栈摘要 + 编译产物摘要（物品签名与 id）+ **被引用附魔的定义摘要**（附魔 id 与其 `max_level`）。必须覆盖子树而不只是本表，否则"子表引用的 item tag 成员变化"（JSON 文本不变、只有展开结果变）不会让父表失效。该哈希一并发给客户端（`CatalogTableDto.hash`），供按需请求声明"我按的是这一版内容"。

**失效保证拆成两句，不要读成一句更大的保证**：

1. **"表 JSON 变化必然失效"**——由资源栈摘要承担，成立；
2. **"影响概率的所有数据变化必然失效"**——并入被引用附魔定义摘要（决定模拟用的满级工具与等级控件范围）后成立。

**已知残余**（不在上述摘要覆盖内，明确列出以免把"没进摘要"一律当成漏洞）：

- 附魔定义的 `max_level` 之外的字段（anvil 花费、权重等）——它们不影响任何概率；
- 其它外部注册表依赖（例如整合包用全局战利品修改器引用的第三方数据）；
- **经核实不受影响、因此不列入残余**的两类：biome tag 成员（`location_check` 由条件指纹回答，不查真实区块与 tag）与 damage type 定义（`DAMAGE_SOURCE` 由 profile 填充，不读注册表）。

统计口径或运行时表来源变化通过 `SIMULATION_CACHE_VERSION` 失效，当前为 `loot-analysis-v20`。函数分级及幸运影响标记加入目录哈希；声明触发率元数据随条件树同步并参与哈希。抽样次数**不再**是全局常量、也不再进表哈希——它现在是输入身份的一维。模拟异常或无法取得有效表时不写入缓存。

**实例态组件排除改变了签名派生结果**，因此该组件 id 列表并入每表哈希的输入：前者覆盖"派生规则本身变了"（第三方 GLM 注入的饰品条目不在静态编译产物摘要里，只靠子树摘要无法失效），后者覆盖"改配置"。两者都只让相关表重算，不需要迁移玩家进度。验证时通过 `/usb journal reload` 强制重算。

**启动只跑基准输入**：由 SavedData 里**该表基准输入**的那一条测量值决定缓存是否命中；未命中才入队。基准输入 = 基准场景（条件全不成立）的条件赋值 + 基准参数（默认工具、幸运 0、基准档位、无附魔）。因此"启动成本"从"每表 × 场景数"降到"每表 × 1"，其余场景在玩家请求时才算。启动日志按原因分账（`概率缓存命中 X 个表的基准输入，Y 个待模拟（未命中原因：哈希变化 A 个、缺少该输入的测量值 B 个）`）：**哈希变化**＝内容变了、本该重算，**缺少该输入的测量值**＝内容没变但这条输入没算过；混成一个数字时"缓存机制坏了"与"内容确实变了"看起来一模一样。判缓存是否正常就看第二次启动能不能把这批表全部命中。

**按需结果只回给请求者，不写共享目录**：按内容去重的缓存是全服共享的，但"当前展示哪个输入"是每个玩家自己的选择，写进共享目录会让两个玩家互相覆盖对方的界面。共享目录里的数字始终是**基准输入**的。

**数据包重载会真正触发重算**：Fabric 用 `ResourceManagerHelper.get(PackType.SERVER_DATA)`、NeoForge 用 `AddReloadListenerEvent` 注册 [`DataPackReloadListener`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/DataPackReloadListener.java)，它**只置脏标记**，重建由 `ServerLootTableConfigManager.tick` 在服务端 tick 路径上消费（在重载回调里同步跑全量构建会拖住重载，且两个平台的重载事件时序不同）。服务端启动时的首次资源加载也会触发监听器，但那时目录尚未加载，标记被忽略。

**不可用机制的表在构建期被拦下**：`LootMechanismSupport` 识别"paramSet 不允许的参数引用"（如 `enchantment_level` 提供器、`enchantment_active_check`）与和填充模型根本不相容的 paramSet（如 `barter`，其 allowed 集合不含 `ORIGIN`）。判定时**空 `type` 按 vanilla 语义等价于 `generic`**（`LootTable.DIRECT_CODEC` 里 `type` 缺省为 `ALL_PARAMS`）——实测有整套模组（BetterArcheology 的 7 张宝箱表）不写 `type`，把空串当成"未知 paramSet"会把它们误判为不可用。这类表不入队模拟（否则会在 `getRandomItems` 里抛异常后静默失败），而是标记为 `Unknown(UNPARSED)` 并输出一次可行动的诊断。

**失败输入不会自动重试**：能确定性失败的情形（注册表里没有该表、条件求值抛异常）用同一份输入重跑只会再失败一次并持续占用 tick 预算，因此失败输入只记录不重排。它的概率保持「未知」（不会显示成 0%），本轮队列排空时汇总列出一次 `N 个模拟输入失败…将在下次数据包重载或 /usb journal reload 时重新尝试`。

`/usb journal reload` 只清除此处的概率缓存与内存目录，不清除玩家笔记进度。

**展示派生只有一份实现**：`ArchaeologyJournalServerCatalog.deriveTable` 从"该表场景规划 + 指定输入的测量值 + 表级发现记录"派生展示用的 `TableDefinition`，**从缓存恢复与刚刚算完都走它**。它因此不可能出现"两条路径两套口径"，也解释了为什么测量值类型（`SimulationMeasurement`）刻意不含展示结论。

## 6. 约束目录与按需管线

**目录只发布约束，不枚举候选输入**。[`SimulationConstraintCatalog`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/SimulationConstraintCatalog.java) 是一张表的四份清单：场景候选（≤32，附截断数量）、工具基座、被引用附魔的等级上限、抽样次数档位。

为什么不枚举：首版计划让目录生成"场景 × 基座工具 × 附魔等级赋值"的完整集合，但那是一个**乘积**——32 的上限只约束其中一维，6 个附魔各 4 档就是 4⁶ × 32 = 131,072 个组合。关键认识是缓存 LRU 限制的是**结果数量**，限制不了这部分**计算**，所以必须从源头取消枚举：单个输入在请求时构造，由 `SimulationConstraintCatalog.resolve` 逐项校验（场景已签发、幸运有限且在界内且已量化到 0.01、工具已签发、每个附魔等级在 `0..max_level`、抽样次数在档位内）。任何一项越权即**整体拒绝，不做部分修正**——把一个越权值悄悄改成最近合法值，会让玩家看到的数字与他填的参数不一致，那比直接拒绝更坏。

**工具与附魔取自整棵子树**（`referenceGraph.descendantsInclusive`）：父表页签里出现的物品来自子表，其 `match_tool` 谓词与读附魔的机制也都写在子表里，只看本表 JSON 会让这些旋钮在父表上凭空消失。工具基座的枚举方式有一条被明确接受的代价：**标签谓词只取首个成员**，因为 `ItemPredicate` 可含任意物品、标签、组件、数量与子谓词，穷举不可能。因此 `ToolOption` 必须同时携带**谓词原文**，提示里才能写清"下拉不等于谓词允许的全部物品"；未列入下拉的成员必然找不到联合见证，于是行为是诚实的降级（只渲染静态提示），而不是挂着一个点不动的死按钮。

**按需请求的完整链路**：

```text
客户端                  服务端主线程                                 存档
──────────              ────────────────────────────────────        ──────────────────
选场景/改旋钮
  → RequestScenarioSimulationPayload
    (generation, 表 id, 表哈希, 场景 key, 幸运, 工具, 附魔等级, 次数)
                        ScenarioSimulationHandler
                          ├─ 每玩家在途 ≤2（worker.canAcceptFor）
                          └─ ScenarioParams 构造（越界即拒绝）
                        ArchaeologyJournalServerCatalog.requestSimulation
                          ├─ 表仍被追踪？ 哈希一致？ 输入被签发？
                          ├─ 命中缓存则不重算
                          └─ 入 HIGH 队列，去重键 (表, 输入键)
                        worker 在主线程分片推进 → 写 SavedData
                          └─ 只回给请求者（不写共享目录）
  ← SyncScenarioResultPayload(generation, 表哈希, inputKey, 单表 DTO)
  ← （被拒绝时）ScenarioRequestRejectedPayload(generation, 表 id, inputKey, 原因)
```

- 三条校验都**必须在服务端**：客户端持有的目录可能已经过期（哈希不一致 → `STALE_HASH`，让它先重新同步），输入可能被自造（→ `REJECTED_INPUT`）。判定的权威是约束描述本身，不是网络层的另一份副本。
- **被拒绝的请求一定回执**：这些情形都不会产出结果包，没有回执的话"点了没反应"与"还在计算中"在界面上无法区分。回执不携带任何概率，也不进缓存。
- 结果包的三个标识缺一不可：**代次**（切参数或 `/reload` 之后旧结果可能后到）、**表哈希**（哈希变了说明这些数字属于上一版内容）、**输入键**（客户端据此判断"这是不是我此刻选中的那个输入"）。三者不符即丢弃。
- 客户端缓存的键与缓存键同源（`表 id + '#' + inputKey`），容量有界、按最旧淘汰。

**目录构建期就要算出约束描述**：启动只跑基准输入，而"基准输入是哪一个"需要先知道基准场景的条件赋值，因此这一步是缓存查询的前置条件，不是可以省的预计算。构建时为每张表打印一行摘要（只对有信息量的表：场景数 >1、有截断、或超预算），运维据此核对上界与降级行为。

## 7. 展示值的派生规则

`ItemDefinition.probability` 是**服务端派生的当前输入展示状态**（`Probability` 四态，可适用性 × 计算状态两轴），`ItemDefinition.scenarioProbabilities` 保存各代表场景的内部统计结果（**同一份参数**下逐个场景查缓存，没算过的场景是 `Unknown(NOT_SIMULATED)`）。

判定规则（`PathHintAnalyzer.deriveDisplay`）：

- 全部获取路径都被**逐路径幸运门槛**证明在任何可表示的幸运下拿不到 → `Unreachable`，网格 `0%`。这是**唯一的静态不可达判据**（有效权重恒为 0）；只有**所有**路径都如此才成立，任一条可达就不写 `0%`。
- 当前输入下适用且测到非零值 → 报测量值；适用但零命中且无路径提示 → `Measured(0.0)`，网格显示「未命中」。
- 当前输入下不适用或测值为零、但路径引用了可调整旋钮/场景条件 → `NeedsCondition`，网格显示「需要条件」，tooltip 逐条列出引用了什么（**只是陈述**，不承诺调完一定能拿到）。
- 上述路径若含未解析条件，则同样保留提示与测量事实，但网格优先显示「条件未解析」；该文案只说明解析缺口，不断言未知条件必然造成零命中。tooltip 列出条件 id，正数测量与静态不可达仍优先。
- 条目在**所有**代表场景中都不适用（条件组合因场景上限被截断）→ `Unknown(UNCOVERED)`，网格 `?`，而不是逐个写 `0`——否则"没算到"会被显示成"不可能获得"。
- 某条路径在某个场景下不可用**不是**静态不可达，它在该场景里记为「需要条件」，不再写 `0%`。

**声明触发率与模拟掉落率分开**：`random_chance` 解析器把常量值或两端皆为常量的 `uniform` 范围写入条件 metadata 的 `declared_chance_min/max`。公共值类型 `DeclaredChance` 从当前页直接路径的条件树（含继承条件及组合条件的叶节点）收集这些值，保序去重。网格的**显示优先级链**固定为：可适用性状态 → （可展示时）声明触发率 → 否则模拟值。即状态为已测量/静态不可达时网格显示"触发率 X%"或范围（多条用 `/` 分隔）；状态是「需要条件」或未知时网格只显示状态词、不显示任何数字，声明值退到 tooltip。tooltip 里两者始终并列并注明各自口径，后者是整张表抽取的统计结果。

声明值描述各个 `random_chance` 节点自身，**不对多个节点做乘法、并集或取反**，也不把权重竞争、rolls 或多 pool 的结果混进声明值；完整条件树用于解释组合关系。动态或非法范围不作为明确声明值，未找到明确值时沿用模拟展示。声明值不套用抽样的两位有效数字或零出现阈值，因此 `0%`、小概率和明确区间均按数据值展示。

**条件展示带保真度三态**，由服务端解析层在 `analyzeAll` 时以 metadata 给出（键 `analysis_fidelity`，见 `LootConditionHandlers`）：描述完整时不写该键，只给出"成立但有未展示约束"的描述时标 `partial`，没有解析器或解析器失败时标 `unreadable`（此时文案统一为"未解析条件：<条件注册名>"，保留 id 作为唯一的定位入口）。JSON 解码失败与条件对象缺少分析器都保留 `unreadable` 节点；无法取得注册 id 时使用明确的占位 id。编译时按表和类型去重告警，便于定位第三方条件。客户端只据该标记映射样式（`partial` 与 `unreadable` 用斜体，`unreadable` 另用 `TooltipBuilder.CONDITION_UNREADABLE` 上色），**不推断条件语义**。同理，拿不到确定数值的概率条件一律承认是动态值（`random_chance` 只在常量或 `uniform` 两端皆为常量时给出百分比），**绝不展示编造的 `100%`**。

`entity_properties` 同时展示已识别的实体类型与 `type_specific` 子谓词，未覆盖的其它字段仍标 `partial`；状态属性的 `min`/`max` 区间显示为可读范围。`value_check`、`table_bonus`、`enchantment_active_check` 和 `random_chance_with_enchanted_bonus` 已补充参数描述，动态或尚未完整解释的值继续标 `partial`。

概率文本与颜色共用**一份**判定：`ItemDefinition.uncertaintyLevel()` 与 `hasConditions()` 读同一批数据（全部获取路径的条件树 + 近似签名及函数分级），UI 不再在客户端另起规则、也不再用 tooltip 文案比较来识别近似条目（那种做法会受语言差异影响）。等级只决定数值的**颜色**；状态词（`?`、「需要条件」「未命中」）一律由服务端派生的 `Probability` 决定，不再出现"颜色说不确定、概率却从不显示 `?`"或反过来的错配。存在场景条件时，概率文字明确标为"条件满足时至少出现一次"，**不是这些条件在自然游戏过程中的发生概率**。

## 8. 重载一致性

整轮重载的静态部分（快照 / 图 / 编译产物 / 静态投影 / 每表哈希 / 分类结构）在同一轮局部构建完成，再通过单个 volatile 引用**原子发布**，读取方只会看到上一代的完整状态或新一代的完整静态部分。模拟结果作为该代的 overlay 随进度增长，提交时校验代次，旧代结果不会写入新代。`invalidate()` 只需丢掉引用，资源快照与投影随之释放。详见 [考古笔记系统](../subsystems/journal.md) 的「目录构建」。
