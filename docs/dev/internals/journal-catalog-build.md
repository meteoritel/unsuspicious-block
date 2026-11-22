# 笔记目录构建细节

> 服务端考古笔记目录的整代构建流程、原子发布与线程模型、冻结快照与哈希同步，以及 `/usb journal` 调试命令边界。
> 本文件不参与主任务导航，由[考古笔记系统](../subsystems/journal.md)与[战利品表系统](../subsystems/loottable.md)按需链接。

## 1. 整代构建流程

入口为 [`ArchaeologyJournalServerCatalog`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/catalog/ArchaeologyJournalServerCatalog.java)，在服务端 `SERVER_STARTED` 阶段调用 `ensureLoaded(server)`：

```mermaid
flowchart TD
    Start["ensureLoaded(server)"] --> Build["buildGeneration(server) 在局部构建整代"]
    Build --> S1["复用本资源代次，编译并投影引用闭包"]
    S1 --> S2["展开当前 Loader 注入并由最终入边推导顶层分类"]
    S2 --> S3["机制支持检查 → 约束目录规划 → 预算诊断"]
    S3 --> S4["标记不可用表并计算每表 SHA-256 哈希"]
    S4 --> S5["构造 CatalogGeneration — 恢复可用基准缓存与不可用展示 overlay"]
    S5 --> Pub["绑定已生效配置和签名策略，原子发布目录"]
    Pub --> Queue["未缓存基准输入入队 LOW 队列 (提交与排空广播均校验 generation)"]
```

### 核心组件协同规则

- **声明注入展开 (`DeclaredInjectionExpansion`)**：在构建期将物品级注入声明与运行时子表边合并为共同贡献，保留来源类型、接入模式、完整函数路径与第一跳子表；构建出的 `ExpandedTable` 同时固定原生、展示与规划三种视图（路径栈用于阻断循环，多父路径分别保留独立门槛）。
- **展示装配 (`CatalogDisplayDerivation` / `CatalogTableDto.assemble`)**：由静态表、约束描述、输入与只读测量快照装配最终 DTO（包含场景假设、物品、子表条件、分支与参数选项），缓存读写与 LRU 触达保留在调用方。
- **完成资格与展示口径分离**：
  - `staticTables()` / `staticTable()` 与 `tracked()` 提供完整声明候选；`completionItems()` 按发布代次的目录层级与物品签名形成完成资格，只接受 `STATIC` 和 `MOD_INTEGRATION` 来源。
  - `displayTable()` 优先读取基准测量，其次读取失败状态，最后使用当前静态表；`displayTables()` 发布这一展示读模型给管理解锁。
  - `baselineCounts()` 依据约束目录和结果 overlay 分别统计待测、已测、失败与机制未解析的表。
- **Worker 交付与重载生命周期**：玩家查看已解锁表且当前选择为待计算的基准输入时，按需请求加入同一个 `(表, 输入)` 任务并提高其优先级。工作器将等待者汇总交付给 `CatalogSimulationSubmission`，目录校验代次、表哈希和输入后提交共享基准，`CatalogSimulationDelivery` 当即回传单表结果。队列排空后广播完整目录哈希。目录只在有效资源代次匹配时执行模拟。资源切换后构建失败仍可读取旧快照。旧任务退役并结束在途请求，新资源上的根表追踪暂停，已捕获的延迟结算继续按原归属。资源未切换时，旧目录继续支持追踪与模拟。

## 2. 冻结目录与按需同步

`CatalogGeneration.readSnapshot()` 是完整目录下发与摘要计算的唯一共享快照接口，封装了当前 `generation`、`overlayRevision`、冻结的目录 Payload 及对应的 SHA-256 摘要：

- **完整静态成员**：每次冻结以静态目录中的表 ID 取展示视图。首次同步即可取得分类、层级、静态候选和折叠签名的依据，后续测量补充概率和运行时发现。抽样失败的表保留候选与获取规则。
- **锁与缓存失效**：模拟 overlay 提交与快照捕获共用同一把锁；每次基准结果提交时递增 `overlayRevision` 并失效摘要缓存。`JournalCatalogHandler` 的哈希比对与全量下发共用同一缓存快照（非主线程调用先切回服务端主线程）。
- **冻结编码与线格式**：快照通过 `SyncArchaeologyCatalogPayload.STREAM_CODEC` 执行一次真实的编码与解码，从而在快照内部提前剥离服务端活谓词与内部约束对象，并固化函数树裁剪结果。SHA-256 摘要直接基于该冻结字节流计算，保证与网络线格式严格同源（协议版本以 `ModPayloads.PROTOCOL_VERSION` 为准，线上限见[网络与同步](../foundation/network.md)）。
- **两级哈希分工**：每表内容哈希用于单表概率缓存准入与按需请求防伪凭据；完整目录 SHA-256 摘要（包含更新后的表数据与 `options` 代次凭据）用于客户端在登录或广播时判定是否需要全量重拉。

## 3. 调试命令与目录稳定性

`/usb journal` 的玩家进度由 `JournalCommand` 提供，共享目录维护由 `JournalCatalogCommand` 提供；根 `/usb` 统一检查 OP 2 权限。

- **`catalog rebuild`**：目录所有者忽略旧缓存构建候选。候选成功后清缓存、发布读模型并切换任务，失败时保留已发布读模型和缓存。用 `catalog status` 查询失败状态、队列和目录覆盖。
- **`catalog status`**：显示队列状态与四种基准终态计数。失败原因见服务端模拟日志，机制不可用原因见目录构建日志。
- **`catalog list`**：列出当前静态目录，使用常规名称解析。缺失翻译仍在目录加载结束时集中汇总。
- **`unlock tables <all|table_id>`**：读取启动即完整的静态视图（`staticTables()`）；选择目标为必填项。
- **`unlock items <all|table_id>`**：等待 worker 空闲及仍待计算的基准任务结束，再按展示视图解锁本表与后代的去重物品；共享子表只遍历一次。完成后有失败或机制未解析终态时，以当前发布候选执行。
- **`reset <all|table_id>`**：修改执行玩家的进度，发送全量状态，再清除相应日志。普通解锁使用增量状态同步。
- **执行者**：玩家进度命令要求玩家源，控制台可用 `/execute as <player> run usb journal ...`；目录维护可直接由控制台执行。裸 `/usb` 根据已注册树生成用法导航。

## 4. 相关文档

- [考古笔记系统](../subsystems/journal.md)
- [战利品表系统](../subsystems/loottable.md)
- [战利品表机制细节](loottable-mechanics.md)
- [网络与同步](../foundation/network.md)
