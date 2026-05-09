# Unsuspicious Block

**Unsuspicious Block** is a Minecraft mod that enhances the archaeology system, allowing you to analyze and extract loot from suspicious blocks without the tedious brushing process — while the **Archaeology Journal** records every discovery along your journey.

> **中文版本**: [README (中文)](../../README.md)

---

## Features

### Archaeology Journal — Core System

The **Archaeology Journal** is the heart of this mod — a personal logbook that automatically records every archaeological discovery you make.

Open the journal to reveal an elegant book interface with two tabs:

| Tab | Function |
|-----|----------|
| **Intro** | Mod introduction and usage guide |
| **Archaeology** | Loot table catalog, displaying all archaeological discoveries |

#### Loot Table Catalog

The journal automatically scans all archaeology loot tables in the game and organizes them by structure:

<!-- TODO: Screenshot - Archaeology Journal main interface -->

![Archaeology Journal Main]()

- **Left Panel — Catalog**: Lists all archaeology loot tables (Desert Pyramid, Desert Well, Cold Ocean Ruin, Warm Ocean Ruin, Trail Ruins, etc.). Unlocked entries are highlighted.
- **Progress Bar**: Shows "Unlocked X/Y" count at the bottom
- **Pagination**: Supports browsing when many mod-added loot tables are present

#### Item Details

Click any loot table in the catalog to reveal all possible drops for that structure:

<!-- TODO: Screenshot - Item grid page -->

![Archaeology Journal Items]()

- **Item Grid**: Displays all possible loot items in a grid layout
- **Parse Progress**: Discovered items show their icons and names; undiscovered items appear as ghost silhouettes with "?"
- **Weight Info**: Hover over items to view drop probability (weight)
- **Acquisition Count**: Tracks how many times each item has been obtained

#### First Discovery Tracking

Each time you brush out a new item, the journal automatically unlocks that entry and records:

- Item name and icon
- Source loot table (structure origin)
- First unlock time (in-game day)

<!-- TODO: Screenshot - First discovery record -->

![First Discovery]()

### Suspicious Reader

The **Suspicious Reader** is an archaeologist's essential tool. Right-click any suspicious block to instantly reveal the hidden loot — no brush, no waiting!

<!-- TODO: Screenshot - Scanning a suspicious block -->

![Suspicious Reader]()

- Right-click suspicious sand/gravel to instantly display the contained item
- Automatically syncs results to the Archaeology Journal
- Compatible with all `BrushableBlockEntity` implementations (including suspicious blocks from other mods)

---

## Optional Integrations

| Mod | Feature |
|-----|---------|
| **[Jade](https://modrinth.com/mod/jade)** (15+) | Displays Suspicious Reader results directly in the Jade HUD overlay |

<!-- TODO: Screenshot - Jade integration -->

![Jade Integration]()

---

## Compatibility

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Mod Loaders** | Fabric, NeoForge |
| **Required Dependencies** | Fabric API *(Fabric only)* |
| **Optional** | Jade (15+) |

---

## License

This mod is licensed under the **MIT License**. Feel free to use it in modpacks, fork it, or contribute!

---

*Made by meteoritel*
