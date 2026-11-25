# Archaeology Journal

The Archaeology Journal tracks what loot you have found and helps you figure out where undiscovered or conditional items come from, what conditions they require, and how likely they are to drop.

## Crafting and Opening

Combine **1 Book** and **1 Brush** in a crafting grid to craft the Archaeology Journal.

![Crafting an Archaeology Journal with a book and a brush](https://raw.githubusercontent.com/meteoritel/unsuspicious-block/1.21.1/docs/image/wiki/en-recipe-archaeology-journal.png)

- Right-click the journal in hand to open it.
- Press `C` (default) while carrying it in your inventory, an accessory slot (with Trinkets / Curios), or a [Specimen Box](en-Specimen-Box).
- Hover over any item in your inventory or JEI and press `C` to search the journal by that item's registry ID and jump directly to matching entries.
- Press `Backspace` or your mouse Back button to step backward through your page history; your reading position is saved per world and per player.

## Home Page and Categories

The right side of the opening page displays your collection stats: brush count, loot chests opened, current log count and noted logs, plus the time and item of your most recent discovery.

The left catalog loads seven categories and table hierarchies right away while calculating drop odds in the background (prioritizing whichever table you are viewing):

- **Brushing**: Suspicious block loot tables.
- **Ruins Chests**: Buried Treasure, Ancient City, and other structure containers.
- **Fishing & Dredging**: Vanilla fishing and Mud Dredging drops.
- **Pots**: Decorated pot loot tables.
- **Panning**: River Shimmers and Nether Glimmers.
- **Fossil Gathering**: Bonus drops from the Fossil Hunter enchantment.
- **Other**: Tracked tables outside the dedicated categories (such as custom or mod-added tables).

## Catalog, Search, and Item Grid

The catalog supports continuous scrolling, `WASD` / arrow-key navigation, `Shift + Click` to favorite entries, sorting (Default, Name, Unlock Status, Item Count, Favorites, Updated), and a **Hide Locked** toggle.

### Search Prefixes

| Syntax | Searches by |
|---|---|
| Plain text | Loot table name |
| `@text` | Mod or namespace |
| `%text` | Loot table type |
| `$text` | Item name |
| `$#text` | Item tag |

### Item Cards and Spoiler Protection

- **Undiscovered items** appear with an unknown icon ("Unparsed") and hide their name, drop odds, and exact rules until you find them.
- **Discovered items** display both **simulated drop odds** and any **configured trigger chance**, along with luck dependency and item modifiers (enchantments, durability ranges, stack counts, etc.).
- Items drawn from the same tag collapse into a group card; child tables referenced by a parent table appear as clickable subtable entries.
- With **JEI** installed, unlocked items on the Intro page and item grid support `U` (uses) and `R` (recipes); closing JEI returns you to the journal.

## Scenarios and On-Demand Odds Simulation

Many drops depend on biomes, weather, open water, tool enchantments, or luck. Use the **Scenarios** bar above the item grid to test your actual setup:

- **Combine World Conditions**: Click the scenario selector to check conditions together (such as biome, rain, or open water).
- **Adjust Tool, Enchantments, and Luck**: Click **Params…** to choose a tool, set enchantment levels, and adjust luck (`-5` to `10`), or click **Sync self** to auto-fill your current location, weather, held tool, enchantments, and luck.
- **Calculate**: Click **Run** to run a 10,000-sample simulation on the server for those inputs (results are cached).
- **Condition Tree & Recommended Setup**: Expand the **Condition tree** (including a zoomable, draggable lightbox view) to inspect branch status, or request a **Recommended setup** for a discovered item or branch to see what conditions and loadout will unlock it.

## Logs and Coordinates

The **Log** tab records every completed discovery, including items and counts, coordinates, dimension, biome, structure, timestamp, and exact source.

- Group logs by time range, dimension, biome, or note status.
- Open a log entry to write a note or copy a `/tp` command back to the site.
- Delete single entries, batch-delete selected entries, clear a table's logs, or clear all logs (deleting logs keeps your unlocked items and advancements intact).
- Configure per-table auto-retention limits or prune old entries immediately; **noted logs are always protected from auto-cleanup**.

## What Unlocks Entries and Progress?

The following actions unlock catalog entries and create logs:

- Brushing suspicious blocks (or extracting scanned loot with the [Archaeological Shovel](en-Reader-and-Shovel))
- Breaking loot-bearing decorated pots
- Catching fishing loot
- Opening loot containers (including chest minecarts and other storage entities)
- [Panning](en-Fishing-and-Panning) or mining natural bone blocks with [Fossil Hunter](en-Lost-Pages-and-Enchantments)

> Scanning with the [Suspicious Reader](en-Reader-and-Shovel) previews a block's contents, but progress and logs only finalize when you actually collect the loot.

Unlocking every static/integration item in a table reaches 100% completion and grants a one-time reward of **1 Ancient Coin**.

## Managing Loot Tables and World Rules

Open **Manage tracked loot tables** from the journal's left sidebar to inspect all tracked and untracked tables:

- Filter by **All / Tracked / Untracked / Recently Encountered**, or search by name or full ID (`namespace:path`).
- See whether a table is a **Top-level catalog entry** or **Included through a parent table**, along with matching rules and parent tables.
- With permission (single-player host or server OP level 2), edit **World Rules** to toggle built-in rules, add prefix/exact rules, or exclude tables, previewing additions, removals, and moves before saving.
- Admins can also edit and import supplemental table names per language (shared across worlds on the server instance; `en_us` must be set first, and resource pack names take priority).

## Multiplayer

Progress, favorites, and logs are saved separately per player. Both client and server must have the mod installed.
