# 网络与同步

公共声明管理 payload 类型、codec 与 C2S 服务端处理；S2C 的客户端接收绑定由 client 持有。本文说明端边界与线路约束，子系统文档维护状态归属和同步时机。

## 声明与数据流

[`core.network.ModPayloads`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/network/ModPayloads.java) 统一维护通道与协议版本：

| 声明 | 消费方式 |
|---|---|
| `C2S_PAYLOADS` | 类型、codec 与服务端 handler，校验请求后转交业务服务 |
| `S2C_PAYLOADS` | 类型与 codec；客户端绑定由 `ClientPayloadBindings` 提供 |

公共 `ModPayloads.S2C<T>` 以类型化常量声明 payload 类型与 codec，聚合进 `S2C_PAYLOADS`。客户端 [`ClientPayloadBindings`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/network/ClientPayloadBindings.java) 维护类型到状态所有者的唯一绑定；平台入口遍历绑定并把接收动作交给 `JournalClientConnection`。公共声明初始化保持独立于客户端实现。

初始化检查同方向通道唯一，客户端绑定同时检查重复项与声明覆盖；绑定工厂接收 `S2C<T>` 声明与 `Consumer<T>`，直接保存声明；平台通过泛型捕获遍历，无需查表强转。具体 payload 查声明；笔记、模拟、猫族与附魔的状态归属和同步时机见对应子系统。

## 端隔离与平台注册

| 平台入口 | 注册职责 |
|---|---|
| Fabric 公共入口 | 登记 C2S/S2C 类型与 codec，安装 C2S receiver |
| Fabric 客户端入口 | 遍历 `ClientPayloadBindings`，绑定客户端 receiver |
| NeoForge 公共入口 | 登记 C2S；专用服务端登记 S2C 类型与 codec |
| NeoForge 客户端入口 | 遍历 `ClientPayloadBindings`，登记 S2C 类型、codec 与客户端 receiver |

NeoForge 握手要求服务端具备客户端声明的必需通道。同一通道在一个物理端登记一次，两端 registrar 使用 `ModPayloads.PROTOCOL_VERSION`。

客户端发送通过 [`ClientNetwork`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/network/ClientNetwork.java)；`ModClientLifecycle.install` 接收当前 Loader 的发送函数。Fabric 客户端直接使用 `ClientPlayNetworking`，NeoForge 客户端使用 `PacketDistributor`。公共 `Services.NETWORK` 仅提供服务端向玩家发送消息的能力。

Fabric C2S 调度到服务器线程，NeoForge handler 使用主线程执行。S2C 保留原连接身份，经 `JournalClientConnection` 在客户端 tick 应用；断连清理维持连接代次，迟到回调按归属丢弃。

源码引用与端归属规则见[仓库工具](../../../tools/README.md)，动态接入和加载相位由平台入口及运行验收核对。

## 目录线格式

完整目录下发和目录 hash 共用 `CatalogReadSnapshot`，快照按 overlay revision 缓存。实际 codec 负责裁剪、稳定编码及 SHA-256 输入，详见[目录构建](../internals/journal-catalog-build.md)。

未知原因只编码规则未覆盖、未解析、尚未模拟和模拟失败；LRU 删除测量后按当前无测量派生。该展示状态不进入概率持久化。

全量目录与按需结果共用 `CatalogStreamCodec` 和 `CatalogTableDto`，读写字段顺序成套变更；线字段变化更新协议版本，双端使用配套发布物。协议版本以 `ModPayloads.PROTOCOL_VERSION` 为准。

- 条件节点编码显式形态、条件身份、逻辑子节点、独立说明行和元数据；`source` 原始谓词只驻留服务端本代，解码后为空。逻辑消费者与展示消费者分别读取各自结构。
- 物品的 `origins` 位图承载来源集合，位于兼容字段 `injected` 后；后者是其兼容投影。
- 获取路径携带有界函数树：类型、本地化描述、保真度、效果类别、自身条件、子函数与元数据。嵌套最多 16 层、元数据最多 32 项、每条路径及每节点子函数最多 64 项，读写共用相应常量。
- 超限子函数由写端裁剪并标记 `truncated=children`，读端按同一限制校验长度。服务端语义树保留业务分析需要的内容，线路传输结构化摘要。
- 函数策略使用 metadata 的 `semantics_v1`，必要策略和裁剪标记优先保留，辅助键按名称稳定排序。策略细节见[战利品表机制](../internals/loottable-mechanics.md)的「函数策略与兼容扩展」。
- `observedFunctions` 位于 `scenarioProbabilities` 后，携带观测链及 `truncated / incomplete / unavailable` 标志；最多 16 条链、每条链最多 64 个节点。输入观测与静态规则树分别表达。
- 条件入口树由服务端根据路径共同条件和注入边门槛派生下发，客户端读取服务端结果。

## 按需模拟的网络约定

玩家选定一个模拟输入（场景 + 参数）后，客户端发 `RequestScenarioSimulationPayload`，服务端回 `SyncScenarioResultPayload`（成功）或 `ScenarioRequestRejectedPayload`（被拒绝）。完整链路与校验语义以 [战利品表系统](../subsystems/loottable.md) 为权威，此处只记网络侧的约定：

- **请求传结构化字段而不是拼好的输入键**：服务端必须能逐项校验（场景、工具、抽样次数只能取签发值，幸运有界）。把校验建立在一个自造字符串上，等于把校验逻辑也写成一个解析器，而解析器的每一处"宽松处理"都是越权的入口。
- **客户端的条件赋值由服务端还原**：客户端只挑场景（传场景 key），不构造条件赋值表。
- **结果包的三个标识缺一不可**：目录代次、表哈希、输入键。三者在客户端都要校验，任一不符即丢弃——切参数或 `/reload` 之后旧结果可能后到，不校验就会把上一代的数据画到当前界面上。
- **被拒绝的请求一定回执**：这些情形都不会产出结果包，没有回执的话"点了没反应"与"还在计算中"在界面上无法区分。回执不携带任何概率，也不进缓存。
- **结果只回给请求者**，不写共享目录：按内容去重的缓存是全服共享的，但"当前展示哪个输入"是每个玩家自己的选择，写进共享目录会让两个玩家互相覆盖对方的界面。
- **推荐/读取（assist）与模拟请求分开**：`SimulationAssistHandler` 只在主动请求时执行，用版本校验 + 每玩家 500ms 冷却拒绝过期或过频请求，通过 `notes` 回传 `assist_busy` / `assist_stale`，不产出概率。
- **推荐先预览后应用**：答复仍绑定原请求序号、目录代次、表哈希及原输入。关闭预览或切表会取消客户端接收资格；15 秒无答复显示超时并允许重试，不能由迟到答复覆盖当前选择。应用只修改输入，模拟仍需显式计算。

## 同步模式

网络层有三种可复用的同步模式，各自的语义与规模限制以下列子系统文档为权威：

| 模式 | 机制要点 | 权威文档 |
|---|---|---|
| 增量 + 全量 | revision + 脏表；客户端检测 revision 间隙（`incomingRevision > lastNotified + 1`）并带 2s 限流主动请求全量 | [考古笔记系统](../subsystems/journal.md) |
| 按需哈希比对 | 服务端算目录 SHA-256，模拟完成后广播；客户端比对不一致才请求全量目录，避免每次登录全量下发 | [考古笔记系统](../subsystems/journal.md) |
| 分片会话快照 | 会话 ID + 增量序号；先发 Start，每表压缩后切成最多 128 KiB 分片，再发 End，客户端校验完一次性替换 | [考古笔记系统](../subsystems/journal.md) |

网络侧的规模上限（线路契约，改这些值等于改协议）：

- 单表压缩后 ≤ 16 MiB；解压 NBT ≤ 64 MiB；一次快照 ≤ 65,536 张表。
- 全量日志快照按表压缩并以字节块传输，避开整份日志的单次 NBT 解码。增量 `SyncJournalLogPayload` 仍使用 `readNbt`，包括 `REPLACE_TABLE`，沿用原版单次 NBT 解码上限。
- 删除 payload 的批量 UUID 列表有单包数量限制，客户端按限制自动拆包。

## 扩展点：新增 payload

1. 在 `payload/c2s/` 或 `payload/s2c/` 创建 payload 类（含 `TYPE` 与 `STREAM_CODEC`）。
2. C2S：在 `ModPayloads.C2S_PAYLOADS` 加条目（type + codec + handler）。
3. S2C：在 `S2C_PAYLOADS` 加一条类型与 codec，再在 `ClientPayloadBindings` 加一条类型化状态绑定。服务端 codec 视图从同一声明派生。
4. **无需修改平台代码**，两端自动遍历注册。
5. 传输目录数据时**必须**走 `CatalogStreamCodec`，不要另写一份字段顺序。
6. 线字段变更时同步更新 `ModPayloads.PROTOCOL_VERSION`，核对共用 codec 和两端接收绑定。

> 新增同步策略时，参考「同步模式」的三种模式，不要把状态推拉逻辑写进 handler —— handler 只做校验与转交。

## 约束与陷阱

- payload 完整声明以 `ModPayloads` 为准，扩展后核对类型、codec 和绑定视图。
- 公共 S2C 声明和服务端入口只使用 type/codec；状态接收函数由客户端绑定清单持有。
- 需要"每玩家独立"的数据不要写进共享缓存或共享目录（见「按需模拟的网络约定」）。

## 相关文档

- [架构总览](architecture.md) —— 平台入口的 payload 注册
- [考古笔记系统](../subsystems/journal.md) —— 同步的上层调度与三种同步模式
- [战利品表系统](../subsystems/loottable.md) —— 按需模拟的完整链路
- [客户端与 GUI](../subsystems/client-ui.md) —— S2C 接收与客户端状态
- [猫族关系系统](../subsystems/cat-favor.md) —— `SyncCatFavorPayload`
- [附魔系统](../subsystems/enchantment.md) —— `SyncEnchantmentRevealListPayload`
- [平台抽象](platform-spi.md) —— `INetworkHelper` SPI
