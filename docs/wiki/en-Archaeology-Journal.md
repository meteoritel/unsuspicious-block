# Archaeology Journal

The Archaeology Journal is this mod's main record-keeping tool. It organizes discovered loot tables, loot actually found, and acquisition conditions into a single interface.

## Obtaining

Combine a book and a brush in a crafting table to make the Archaeology Journal.

- Right-click the journal in hand to open it.
- Press `C` (default) to open a journal you are carrying.
- When opened via the hotkey, the journal can sit in your inventory; with a Curios/Trinkets integration installed, it can also rest in an accessory slot.
- Hover over any item in your inventory and press `C` to search the journal by the item's registry name; if there is a match, the journal opens directly at that entry. With JEI installed, hovering over the JEI item list works the same way.

## Home Page

The right side of the first page after opening the journal shows collection statistics: number of brushes, loot chests opened, current log entries and noted entries, plus the time of the latest record and the most recently discovered item.

## Catalog

The Catalog is used to view loot tables and the items that can appear in them. As you explore, new loot tables and items unlock gradually.

The Catalog supports:

- Searching and sorting
- Favoriting entries
- Hiding locked (undiscovered) content
- Viewing item acquisition conditions and estimated probabilities
- Viewing source relationships between nested loot tables

Probabilities are estimates based on the loot tables and their conditions. Actual results are still affected by random chance, biomes, dimensions, tool enchantments, and other loot conditions.

## Log

The Log records discoveries that have actually happened, including:

- Items found and their quantities
- Coordinates, dimension, and biome
- Structure or container source
- Time of discovery
- The loot table path associated with the discovery

Logs can be grouped by time, dimension, biome, and note status. Opening a log entry's details lets you add a note and copy a teleport command for returning to the discovery site.

Log entries are stored separately per loot table. Servers can set a retention cap per table; once the cap is reached, the oldest unprotected records are cleaned up.

## What Unlocks Records

The following actions advance the journal's catalog and collection progress:

- Brushing suspicious blocks
- Excavating decorated pots
- Catching fishing loot
- Opening loot chests

The Suspicious Reader's scan results help you learn where suspicious blocks are and what they contain, but the Log records a full discovery only when you actually obtain the loot.

## Collection Progress and Rewards

When every item in a tracked loot table and its child tables has been unlocked at least once, that table reaches 100% completion and grants a one-time reward of 1 Ancient Coin.

## What Can Be Tracked

By default, the mod recognizes common archaeology loot tables, decorated pots, fishing, buried treasure, the Ancient City, and the mod's own special loot. Different modpacks may add new loot tables, so the catalog's contents change with the installed mods and datapacks.

## JEI Integration

With JEI installed, item entries in the journal's catalog support JEI's `U` (uses) and `R` (recipes) hotkeys; closing the JEI overlay returns you to the Archaeology Journal automatically.

## Multiplayer

Journal progress and logs are saved per player. On multiplayer servers, every player has their own unlock state and discovery records; the mod must be installed on both the client and the server.
