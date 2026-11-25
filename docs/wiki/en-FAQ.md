# FAQ

## Can I add this mod to an existing world?

Yes.

- Temporary river **Shimmers** spawn naturally near players in river biomes, even in previously explored chunks.
- Permanent worldgen panning spots (river **Shimmers** and Nether **Glimmers**) generate in newly explored chunks.
- Unopened loot chests and untouched suspicious blocks can still roll mod loot when first opened or brushed. For structures you have already looted, you can use [Rewind Dust](en-Spark-and-Rewind-Dust) to regenerate them in place.

## Why won't the Archaeological Shovel break a suspicious block?

This is intentional protection against accidental destruction: normal left-clicking does not break vanilla suspicious blocks or trigger vein digging. Hold **Shift (sneak)** to mine one intentionally, or scan it with the Suspicious Reader and **right-click** with the shovel to extract the loot directly.

## Why can't I extract loot from a block my friend scanned?

Scan ownership is tracked per player. Each player must scan a suspicious block with their own Suspicious Reader before extracting it with the Archaeological Shovel.

## Why do some items in the Archaeology Journal show `?` or "Condition needed"?

- **`?` (Not computed yet)**: After joining a world, the journal displays categories and candidate items immediately while computing probabilities in the background, prioritizing whichever table you have open. Wait a moment for the simulation to finish.
- **`Condition needed` / `No hit`**: That drop depends on specific conditions (such as biome, open water, weather), a tool or enchantment, or higher luck. Use the **Scenarios** bar above the grid to adjust conditions and loadout and click **Run**, or use **Recommended setup** on a discovered item.

## Where do I find each special mod item?

| Item | Where to Find It |
|---|---|
| **Ancient Coin** | Trail Ruins common suspicious blocks (5%), river panning, digging sand/gravel with Archaeological Shovel (0.3%), Fossil Hunter drops, 100% journal table completion reward |
| **Lost Page** | Trail Ruins rare suspicious blocks (8%), river panning, Fossil Hunter drops |
| **Eye of Cat** | Buried Treasure chests (100% chance for 1) |
| **Specimen Box** | Village Weaponsmith chests (30% chance) |
| **Purrmint Seeds** | 50% bonus drop whenever a Sniffer digs up ancient seeds |

## Will Rewind Dust overwrite my base if I built inside a structure?

**Yes.** Rewind Dust re-runs world generation rather than restoring a snapshot backup. Any player builds or edits inside the structure's bounding box will be overwritten, and chests/entities will regenerate. Never use it inside a base or storage area.

## How do I report a bug or suggest a feature?

Open an issue on [GitHub Issues](https://github.com/meteoritel/unsuspicious-block/issues) and include:

- Your Minecraft version, loader (Fabric / NeoForge) and version, and Unsuspicious Block version
- Relevant optional mods (such as JEI, Jade, Create, Mekanism, or Lootr)
- Clear reproduction steps and any crash log
- For multiplayer issues, whether the issue happens on the client or the server
