# 更新日志 Change log

## [1.0.0]
添加了可疑扫描仪，实现与jade的联动。

## [1.1.0]

### 新增
- 添加了考古手册，现在可以记录考古信息了。

## [1.2.0]

### 新增

#### 考古手册「日志」标签页
- 新增「日志」标签页，完整记录玩家每次考古相关事件

#### 可自定义追踪的战利品表
- 追踪战利品表现支持通过配置文件自定义，支持 `namespace:path` 精确匹配与 `path/`前缀批量匹配。详见配置说明请参考 `README_CN.txt` / `README_EN.txt`（NeoForge 下位于配置注释，Fabric 下位于配置目录）。
- 新增对考古、战利品箱、钓鱼行为的日志支持，玩家可在配置文件中自由添加需要追踪的战利品表。

#### 自动生成翻译键与缺失翻译键导出
- **缺失翻译键自动导出**：运行时检测到考古手册中存在未本地化的战利品表名时，会自动将缺失翻译键追加写入游戏目录下的 `usb_miss_key/missing_keys.json`，value 预填为战利品表的清洗名称，方便直接修改后合并回模组语言文件。
  - **为配置中新添加的战利品表补全翻译的步骤**：
    1. 在配置文件 `unsuspiciousblock.json` 的 `archaeology_path_prefixes` 中添加目标表规则（如 `mymod:archaeology/`），重载游戏；
    2. 进入游戏世界，触发考古手册目录加载（打开手册或执行 `/usb debug table_list`）；
    3. 查看 `usb_miss_key/missing_keys.json`，其中已自动列出所有缺失的翻译键及预填名称；
    4. 将该文件中的键值对合并到模组的 en_us.json 与 zh_cn.json，并将 value 修改为正式翻译即可。

#### 新增 4 种附魔
- **化石猎手**（Fossil Hunter）：最高 1 级，适用于镐子。破坏自然生成的骨块时，有 50% 概率额外 roll 一份对应维度的骨块掉落表；玩家放置的骨块不触发此效果。
- **织物采集**（Textile Recovery）：最高 1 级，适用于剪刀。剪羊毛时有 30% 概率额外掉落 1~2 根线。
- **泥地打捞**（Mud Dredging）：最高 3 级，适用于钓鱼竿。在开放水域钓鱼收杆时，每级提供 10% 概率（满级 30%）将原版战利品表替换为自定义沼泽掉落表；若当前群系注册名包含 "swamp"，额外提升 10% 概率。
- **精掘**（Precision Excavation）：最高 3 级，适用于刷子。刷拭可疑方块时，按等级概率（1 级 16% / 2 级 36% / 3 级 60%）使战利品翻倍。

#### 新增 4 种物品
- **考古铲**（Archaeological Shovel）：铁铲等级工具，可一次垂直挖掘 3 格，不会破坏可疑方块及其支撑方块。挖掘沙子时小概率产出古代金币。
- **古代金币**（Ancient Coin）：可在古迹废墟战利品中发现，用于为可疑扫描仪充能。
- **失落书页**（Lost Page）：可在古迹废墟稀有战利品中发现，与基页和书本在锻造台中合成附魔书。
- **基页**（Base Page）：合成材料。

#### 新增一系列调试用的指令
- 本模组指令以 `/usb `开头，均需要op权限。

### 修改

#### 考古手册
- 战利品概率展示现改为基于 10000 次模拟抽取获得的近似值，首次启动服务端时可能需要数秒进行计算。
- 注意：部分战利品需满足特定条件方可获得，概率显示仅供参考。

#### 可疑扫描仪
- 重制配方与纹理。
- 新增范围模式，默认按 V 键切换扫描范围，潜行时显示扫描范围边界。
- 范围模式下会消耗能量，并可自动消耗背包中的古代金币进行充能。

### Added

#### Archaeology Handbook – "Log" Tab
- Added a **"Log"** tab that fully records every archaeology-related event for the player.

#### Customizable Trackable Loot Tables
- Loot table tracking now supports configuration via config file, with `namespace:path` exact matching and `path/` prefix batch matching. For detailed configuration instructions, refer to `README_CN.txt` / `README_EN.txt` (under NeoForge, located in config comments; under Fabric, in the config directory).
- Added logging support for archaeology, loot chests, and fishing. Players can freely add loot tables to track in the config file.

#### Automatic Translation Key Generation & Missing Key Export
- **Auto-export of missing translation keys**: At runtime, when the Archaeology Handbook detects unlocalized loot table names, missing keys are automatically appended to `usb_miss_key/missing_keys.json` in the game directory, with the value pre-filled as the cleaned name of the loot table for easy modification and merging back into the mod's language files.
  - **Steps to complete translations for newly added loot tables in the config**:
    1. Add the target table rule (e.g., `mymod:archaeology/`) to `archaeology_path_prefixes` in `unsuspiciousblock.json`, then reload the game.
    2. Enter the game world and trigger the Archaeology Handbook catalogue load (open the handbook or run `/usb debug table_list`).
    3. Check `usb_miss_key/missing_keys.json` – all missing translation keys with pre-filled names are listed.
    4. Merge the key-value pairs from that file into the mod's `en_us.json` and `zh_cn.json`, and change the values to the final translations.

#### 4 New Enchantments
- **Fossil Hunter**: Max level 1, applicable to pickaxes. When breaking naturally generated bone blocks, has a 50% chance to additionally roll the bone block drop table for the corresponding dimension. Does not trigger on player-placed bone blocks.
- **Textile Recovery**: Max level 1, applicable to shears. When shearing sheep, has a 30% chance to drop 1–2 extra string.
- **Mud Dredging**: Max level 3, applicable to fishing rods. When reeling in while fishing in open water, each level provides a 10% chance (30% at max level) to replace the vanilla loot table with a custom swamp drop table. If the current biome registry name contains "swamp", the chance is increased by an additional 10%.
- **Precision Excavation**: Max level 3, applicable to brushes. When brushing suspicious blocks, has a per-level chance (Level 1: 16% / Level 2: 36% / Level 3: 60%) to double the loot.

#### 4 New Items
- **Archaeological Shovel**: Iron shovel-tier tool that can dig 3 blocks vertically in one go. Does not destroy suspicious blocks or their supporting blocks. When digging sand, has a small chance to yield Ancient Coins.
- **Ancient Coin**: Can be found in trail ruins loot. Used to recharge the Suspicious Scanner.
- **Lost Page**: Can be found in trail ruins rare loot. Combined with a Base Page and a Book in a smithing table to craft an Enchanted Book.
- **Base Page**: Crafting material.

#### New Debug Commands
- All mod commands are prefixed with `/usb ` and require operator permissions.

### Changed

#### Archaeology Handbook
- Loot probability display is now based on approximate values from 10,000 simulated draws. The first startup on the server side may take a few seconds to compute.
- Note: Some loot requires specific conditions to be obtained; the displayed probabilities are for reference only.

#### Suspicious Scanner
- Recipe and texture reworked.
- Added a range mode, default toggle key is V. While sneaking, the scanning range boundary is displayed.
- Range mode consumes energy and can automatically consume Ancient Coins from the inventory for recharging.

## [1.3.0]

### 新增

#### 可疑扫描仪：Shift + 右键空气充能
- 手持可疑扫描仪时，Shift + 右键点击空气可使用背包中的古代金币补充能量，每枚金币恢复 256 点能量。
- 浪费保护机制：当剩余可充能量不足一枚金币的充能值时，首次 Shift + 右键会提示浪费风险；若在 1 秒内再次 Shift + 右键，则强制消耗金币进行充能，以避免误操作导致资源浪费。

#### 古代金币铁砧修复
- 古代金币现可用于铁砧修复带耐久值的物品，每枚金币修复目标物品最大耐久的 25%。
- 使用古代金币进行修复不会累加附魔惩罚。

#### 考古手册收藏功能与日志备注
- 现支持通过 Shift + 点击收藏战利品表。
- 日志新增备注功能，被备注的日志将不会被自动销毁。

#### 嵌套战利品表追踪
- 现支持嵌套战利品表的追踪，并标注子表来源（仅当子表同时处于追踪状态时生效）。此修复应解决上个版本中钓鱼战利品表无法追踪的问题。

#### 猫之瞳
- 新增物品「猫之瞳」，可在埋藏的宝藏战利品中发现。装备于护符槽或携带在背包中时，激活以下能力：
  - **附魔候选揭示**：在附魔台界面中展示完整的附魔候选列表。
  - **铁砧成本分解**：在铁砧界面中悬停物品时，追加成本分解提示框。
  - **砂轮操作分解**：在砂轮界面中悬停物品时，追加操作预览提示框。

#### 饰品
- 新增与饰品栏的联动支持：NeoForge 端使用 Curios API，Fabric 端使用 Trinkets。

### 变化

#### 附魔调整
- **泥地打捞**：附魔权重从 2 降至 1，以降低其在附魔台中的出现概率；在沼泽群系中触发后，将分流至专属战利品表（奖励更丰厚）。
- **织物采集**：修改为必定掉落 1～3 根线（随机数量）。
- **精准发掘**：概率模型从硬编码数组改为公式计算。各等级概率调整为：1 级 16% → 28%，2 级 36% → 44%，3 级保持 60% 不变，同时移除了等级上限约束。

#### 调试指令统一为子系统分组结构
- 所有调试指令仍以 `/usb` 开头，需要 OP 权限等级 2。现按子系统重构为子树结构，当前已实现 `journal`（考古笔记）子系统。
- **考古笔记 `/usb journal ...`**
  - `clear [table_id]`：清空玩家考古笔记数据。不带参数时清空全部数据；指定 `table_id` 则仅清除该战利品表的数据。
  - `unlock table [table_id]`：解锁考古战利品表。不带参数时解锁全部表；指定 `table_id` 则仅解锁该表。
  - `unlock item [table_id]`：解锁考古物品条目。不带参数时解锁全部表中的所有物品；指定 `table_id` 则仅解锁该表中的全部物品。
  - `reload`：强制清空概率缓存，重新加载并重新模拟所有被追踪的战利品表概率。
  - `list`：列出当前服务端已加载的所有考古战利品表，包含其翻译键、显示名称等信息。

### 修复
- 修复了钓鱼战利品表无法正常追踪的问题。
- 修复了药水类物品无法正常解析的问题。
- 修复了注入古迹废墟的考古战利品无法正常获取的问题。
- 修复了基页配方中误用 `pitcher_pod` 的问题（现已改用 `pitcher_plant`）。

### Added

#### Suspicious Reader: Shift + Right-click Air Charging
- While holding the Suspicious Reader, Shift + right-clicking in air consumes Ancient Coins from your inventory to recharge energy, with each coin restoring 256 energy.
- Waste protection: When the remaining rechargeable energy is less than one coin's worth, the first Shift + right-click warns of potential waste; if Shift + right-click is performed again within 1 second, the coin is forcibly consumed to charge, preventing accidental resource waste.

#### Ancient Coin Anvil Repair
- Ancient Coins can now be used on an anvil to repair items with durability, with each coin restoring 25% of the target item's maximum durability.
- Repairing with Ancient Coins does not accumulate enchantment penalties.

#### Archaeology Journal Collection Feature and Log Annotations
- Shift + clicking now supports collecting loot tables.
- Logs now have an annotation feature; annotated logs will not be automatically destroyed.

#### Nested Loot Table Tracking
- Support for tracking nested loot tables has been added, with sub-table sources now labeled (only effective when the sub-table is also being tracked). This fix should resolve the issue from the previous version where fishing loot tables could not be tracked.

#### Eye Of Cat
- New item "Eye Of Cat", found in buried treasure loot. When equipped in the charm slot or carried in the inventory, activates the following abilities:
  - **Enchantment Candidate Reveal**: Displays the full list of enchantment candidates in the enchantment table interface.
  - **Anvil Cost Breakdown**: Adds a tooltip with a cost breakdown when hovering over items in the anvil interface.
  - **Grindstone Operation Breakdown**: Adds a tooltip with an operation preview when hovering over items in the grindstone interface.

#### Trinket
- Added compatibility with trinket slots: NeoForge uses Curios API, Fabric uses Trinkets Mod.

### Changed

#### Enchantment Adjustments
- **Mud Dredging**: Enchantment weight reduced from 2 to 1 to lower its appearance rate in the enchantment table; when triggered in swamp biomes, it now diverts to a dedicated loot table (with better rewards).
- **Textile Recovery**: Changed to always drop 1–3 string (random quantity).
- **Precision Excavation**: Probability model changed from a hardcoded array to a formula-based calculation. Adjusted probabilities per level: Level 1: 16% → 28%, Level 2: 36% → 44%, Level 3 remains 60%. 

#### Commands Unified into Subsystem Group Structure
- All commands still start with `/usb` and require OP permission level 2. They are now restructured into subtrees per subsystem. Currently, the `journal` (archaeology journal) subsystem is implemented.
- **Archaeology Journal `/usb journal ...`**
  - `clear [table_id]`: Clears the player's archaeology journal data. Without arguments, clears all data; with `table_id`, clears only that loot table's data.
  - `unlock table [table_id]`: Unlocks an archaeology loot table. Without arguments, unlocks all tables; with `table_id`, unlocks only that table.
  - `unlock item [table_id]`: Unlocks archaeology item entries. Without arguments, unlocks all items in all tables; with `table_id`, unlocks all items in that table only.
  - `reload`: Forcefully clears probability caches, reloads and re-simulates probabilities for all tracked loot tables.
  - `list`: Lists all archaeology loot tables currently loaded on the server, including their translation keys, display names, and other info.

### Fixed
- Fixed an issue where fishing loot tables could not be tracked properly.
- Fixed an issue where potion items could not be parsed correctly.
- Fixed an issue where archaeology loot injected into trail ruins could not be obtained properly.
- Fixed a mistake in the base page recipe that used `pitcher_pod` (now correctly uses `pitcher_plant`).

## [1.4.0]

### 新增
- 完善之前未完成的标本箱功能

### 变化


### 修复