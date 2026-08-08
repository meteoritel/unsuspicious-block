# Unsuspicious Block

**Unsuspicious Block** is a Minecraft mod about archaeology, loot collection, and documenting discoveries. It adds tools for scanning and quickly excavating suspicious blocks, plus an Archaeology Journal that grows into a detailed record of loot, probabilities, locations, and excavation history.

> 中文版: [README](../../README.md)

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/overview.png
Scene: Hold a Suspicious Reader in a Trail Ruins site with highlighted suspicious blocks visible. Put the Archaeology Journal and Archaeological Shovel on the hotbar.
Suggested format: 16:9, 1920x1080, debug overlay hidden, HUD visible.
Replace the placeholder below with: ![Unsuspicious Block overview](../image/readme/overview.png)
-->

> **Screenshot needed: gameplay overview** (target: `docs/image/readme/overview.png`)

## Requirements

| Item | Support |
|---|---|
| Minecraft | 1.21.1 |
| Loader | Fabric 0.17.0+ / NeoForge 21.1.195+ |
| Java | 21 |
| Current version | 1.5.0 |
| Multiplayer | Install on both client and server |
| License | MIT |

The Fabric build requires [Fabric API](https://modrinth.com/mod/fabric-api). The NeoForge build has no additional required dependency.

## Features

### Archaeology Journal

Craft an Archaeology Journal with a book and a brush. Right-click it, or press `C` while carrying it, to open the journal.

The journal organizes your exploration into two main views:

- **Catalog:** Browse recognized loot tables, possible items, acquisition conditions, and estimated probabilities. Search, sort, favorite entries, or hide locked content.
- **Log:** Review actual finds with their coordinates, dimension, biome, structure, and time. Group records by time, dimension, biome, or note status, add personal notes, and copy teleport commands.
- **Collection progress:** Brushing suspicious blocks, excavating pots, fishing, and triggering the mod's special loot enchantments gradually unlock entries and archaeology challenges.
- **Datapack support:** Common `archaeology/`, `archeology/`, fishing, pot, and fossil loot table paths are recognized by default. Modpack authors can extend the matching rules through configuration.

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/journal_catalog.png
Scene: Show the Journal catalog with both locked and unlocked tables, plus an expanded item entry with probability and conditions.
Suggested format: 16:9 or a tight UI crop at GUI scale 2.
Replace the placeholder below with: ![Archaeology Journal catalog](../image/readme/journal_catalog.png)
-->

> **Screenshot needed: Journal catalog and item details** (target: `docs/image/readme/journal_catalog.png`)

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/journal_log.png
Scene: Show a selected log entry with coordinates, biome, structure, actual loot, and a player note.
Suggested format: 16:9 or a tight crop with readable text.
Replace the placeholder below with: ![Archaeology Journal excavation log](../image/readme/journal_log.png)
-->

> **Screenshot needed: Journal excavation log** (target: `docs/image/readme/journal_log.png`)

### Suspicious Reader and Archaeological Shovel

The common archaeology loot pool in Trail Ruins has a 5% chance to yield an **Ancient Coin**. Coins are used to craft and power the Suspicious Reader.

- Right-click a suspicious block with the Reader to reveal its contained loot without extracting it.
- Press `V` to cycle between single-block, `3x3x3`, `5x5x5`, and `7x7x7` scan modes.
- Single-block scans are free. Area scans consume energy and highlight suspicious blocks and unopened loot containers in range.
- The Reader stores up to 512 energy. Sneak and right-click the air to consume one Ancient Coin and restore 256 energy.
- Right-click a scanned suspicious block with the Archaeological Shovel to extract its loot immediately, without completing the normal brushing process.
- The Shovel digs downward through up to 3 shovel-mineable blocks. Sneaking restores single-block mining, and the chain-dig behavior protects suspicious blocks and their supports.

The Shovel also has a 0.3% chance to uncover Ancient Coins while mining sand, red sand, or gravel. Ancient Coins can repair any damageable item in an anvil, restoring 25% of its maximum durability per coin. Wandering Traders may also offer 5 Emeralds for 1 Ancient Coin.

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/suspicious_reader.png
Scene: Show an area scan in ruins with block outlines, the chat scan result, and the Reader's energy bar. The off hand may hold the Archaeological Shovel.
Suggested format: 16:9, 1920x1080.
Replace the placeholder below with: ![Suspicious Reader area scan](../image/readme/suspicious_reader.png)
-->

> **Screenshot needed: Reader area scan and Shovel combination** (target: `docs/image/readme/suspicious_reader.png`)

### Lost Pages and Four Enchantments

The rare archaeology loot pool in Trail Ruins has an 8% chance to yield a **Lost Page**. Craft 3 Base Pages from 3 pitcher plants, then place a Base Page, a book, and a Lost Page into a smithing table to create a random enchanted book. The result may contain mod enchantments, vanilla enchantments, or both.

| Enchantment | Item | Effect |
|---|---|---|
| Mud Dredging I-III | Fishing rod | Adds a chance to dredge up extra treasure: +10% per level, with another +15% in swamps |
| Textile Recovery | Shears | Drops 1-3 extra string when shearing sheep |
| Precision Excavation I-III | Brush | Has a 28% / 44% / 60% chance to double suspicious-block loot |
| Fossil Hunter | Pickaxe | Has a 50% chance to roll extra dimension-specific fossil loot when mining naturally generated bone blocks |

`Fossil Hunter` only works on bone blocks recorded during world generation. Blocks placed by players or machines do not qualify.

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/enchantments.png
Scene: Combine the Lost Page smithing recipe with four enchanted books showing the mod enchantment names.
Suggested format: a horizontal composite with readable item and enchantment names.
Replace the placeholder below with: ![Lost Pages and mod enchantments](../image/readme/enchantments.png)
-->

> **Screenshot needed: Lost Page smithing and four enchantments** (target: `docs/image/readme/enchantments.png`)

### Eye of Cat

Every buried treasure chest contains an Eye of Cat. Keep it in your inventory, an accessory slot, or a Specimen Box to gain three information abilities:

- Reveal the full list of possible enchantments for each enchanting-table option instead of only the vanilla clue.
- Break down anvil level costs into enchantment, repair, rename, incompatibility, and prior-work penalties.
- Preview which enchantments a grindstone removes, which curses remain, and the expected experience refund.

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/eye_of_cat.png
Scene: Show the full enchanting-table candidate tooltip while carrying the Eye of Cat, with a smaller anvil or grindstone breakdown panel.
Suggested format: a horizontal composite focused on tooltip changes.
Replace the placeholder below with: ![Eye of Cat interface information](../image/readme/eye_of_cat.png)
-->

> **Screenshot needed: Eye of Cat enchanting, anvil, and grindstone information** (target: `docs/image/readme/eye_of_cat.png`)

### Specimen Box

Village weaponsmith chests have a 30% chance to contain a Specimen Box. This portable container has 5 slots: right-click to open it, hover to preview its contents, and keep it in your inventory or an accessory slot to preserve carry or equipped effects from compatible items stored inside.

With Trinkets on Fabric or Curios on NeoForge, the Specimen Box can be equipped in an accessory slot and proxies compatible attributes and effects from its contents. Optional support for Artifacts accessories is also included.

<!-- SCREENSHOT PLACEHOLDER
File: docs/image/readme/specimen_box.png
Scene: Show the five-slot Specimen Box GUI containing an Archaeology Journal, Eye of Cat, and compatible accessories, plus the inventory tooltip preview.
Suggested format: a tight crop with clear slots and tooltip text.
Replace the placeholder below with: ![Specimen Box interface](../image/readme/specimen_box.png)
-->

> **Screenshot needed: Specimen Box GUI and content preview** (target: `docs/image/readme/specimen_box.png`)

## Getting Started

1. Craft an Archaeology Journal from a book and a brush, then keep it with you.
2. Find Trail Ruins and brush suspicious blocks to obtain your first Ancient Coins and Lost Pages.
3. Craft a Suspicious Reader and Archaeological Shovel. Scan individual blocks before extracting their loot, then move to area scans when you have spare Coins.
4. Turn Lost Pages into enchanted books and work toward Precision Excavation, Fossil Hunter, Textile Recovery, or Mud Dredging.
5. Use the Journal catalog, logs, and challenges to continue collecting pottery sherds, smithing templates, and other rare loot.
6. Search buried treasure and village weaponsmith buildings for the Eye of Cat and Specimen Box.

JEI, REI, or EMI is recommended for viewing recipes, but no recipe viewer is required.

## Optional Integrations

| Mod | Integration |
|---|---|
| Jade | Displays scanned suspicious-block loot in the block information overlay |
| Trinkets | Fabric accessory support for the Journal, Eye of Cat, and Specimen Box |
| Curios | NeoForge accessory support for the Journal, Eye of Cat, and Specimen Box |
| Artifacts | Lets the Specimen Box proxy compatible accessory effects |
| Mod Menu | Fabric configuration screen entry |
| Lootr | The NeoForge build offers optional compatibility for per-player excavation and journal state |

## Configuration

- Fabric: `config/unsuspiciousblock/unsuspiciousblock.json`; with Mod Menu installed, it is also available from the mod list.
- NeoForge: use the NeoForge mod configuration screen and configuration file.
- Configurable values include archaeology loot-table matching rules, retained log entries per table, and loot-container tracking timeout.

## Links

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/unsuspicious-block)
- [Source Code](https://github.com/meteoritel/unsuspicious-block)
- [Issue Tracker](https://github.com/meteoritel/unsuspicious-block/issues)
- [MIT License](../../LICENSE.txt)
