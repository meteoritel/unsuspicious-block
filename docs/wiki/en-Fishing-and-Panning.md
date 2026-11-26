# Fishing and Panning

Beyond brushing ruins, you can fish with Mud Dredging to pull extra cargo from open water, or use pans at shimmering river and Nether lava spots to wash out clay, minerals, and relics.

## Mud Dredging

Enchanting a fishing rod with [Mud Dredging](en-Lost-Pages-and-Enchantments) (I–III) adds a chance to catch 1 bonus item per reel-in: **20%** at Level I, increasing by **10%** per extra level (**40%** at Level III).

### Shallow vs. Open Water

- **Shallow Water**: Only pulls basic silt items (clay balls, mud, glass bottles, bone meal, lily pads).
- **Open Water**: Unlocks regional loot pools; if **Relics** is installed, open-water catches also have a **2%** chance to roll a Relics item.

### Regional Pools

- **Non-Swamp Open Water**:
  - **Driftwood Cargo**: Paper, string, leather, bowls, compasses, and clocks.
  - **Inland Freshwater** (outside oceans and beaches): Adds lanterns, Bottles o' Enchanting, and emeralds.
  - **Frozen & Badlands Waters**: Frozen rivers/oceans/snowy beaches add packed ice; badlands waters add gold nuggets.
  - **Warm & Cold Oceans and Beaches**: Drop biome-grouped pottery sherds (Warm: Angler/Shelter/Snort; Cold: Blade/Explorer/Mourner/Plenty), **Coast Armor Trim Smithing Templates**, and **Shipwreck Barrels**.
  - **Shipwreck Barrels**: Place and open a dredged barrel to roll full vanilla Shipwreck Map or Shipwreck Treasure chest loot.
- **Swamp & Mangrove Swamp Open Water**: Pulls from a dedicated swamp pool containing sticks, sugar, spider eyes, gunpowder, redstone, glowstone dust, slime balls, mangrove propagules, fermented spider eyes, damaged enchanted golden axes, Water Breathing / Night Vision / Poison potions, and Music Disc `13`.

> Ancient Coins and Lost Pages do not drop from Mud Dredging.

## Panning

### Crafting the Three Pans

All three pans can be repaired on an anvil with their base material and accept Durability enchantments (such as Unbreaking and Mending):

| Pan | Crafting Recipe | Durability | Anvil Repair | Targets & Traits |
|---|---|---:|---|---|
| **Copper Pan** | 4 copper ingots + 1 iron nugget | 32 | Copper Ingot | River **Shimmers** only; baseline luck for steady panning. |
| **Gold Pan** | 4 gold ingots + 1 gold nugget | 24 | Gold Ingot | River **Shimmers** only; grants **`+1.0` luck bonus** by default and a **10%** chance by default to create a nearby special spot on each successful harvest of a natural spot. |
| **Obsidian Pan** | 4 obsidian + 1 crying obsidian | 64 | Obsidian | **The only pan usable on Nether Glimmers**; can pan water as well, but applies a **`-1.0` luck penalty** in rivers, lowering mineral silt and preventing gold nuggets, riverbed minerals, and Lost Pages. |

Arrange ingredients in a U-shape in a crafting table (top-left/top-right main material, bottom-left/bottom-right main material + bottom-center core):

- Copper Pan: `Copper _ Copper` / `Copper Iron_Nugget Copper`
- Gold Pan: `Gold _ Gold` / `Gold Gold_Nugget Gold`
- Obsidian Pan: `Obsidian _ Obsidian` / `Obsidian Crying_Obsidian Obsidian`

### How Panning Works & Spot Types

Look at a panning spot and hold right-click for **5 seconds** (100 ticks) to finish one pan, consuming **1 durability** and **1 use** on the spot (default **3 uses** per spot). The surface glow dims as uses drop, and loot launches toward you upon completion.

- **Shimmers (Water)**:
  - **Worldgen Spots (Permanent)**: Generate with new chunks, ignore the dimension cap, and never despawn naturally. Empty worldgen spots keep emitting faint rising particles and gradually refill 1 use every **20–40 minutes**.
  - **Natural Spots (Temporary)**: Attempt to spawn near players in rivers roughly every **30 seconds** (with a "Something fell into the water" alert), lasting **20–40 minutes** (default cap of 5 per dimension). Depleting one puts the surrounding `3×3` chunks on a 30-minute spawn cooldown.
  - **Special Spots (Temporary)**: A bonus from harvesting natural spots with a Gold Pan. It spawns on the nearest available water surface at the same level, ignores natural spawning limits and cooldowns, and cannot generate another spot.
  - **Frozen Water**: If the water beneath a Shimmer freezes into regular ice, panning pauses until the ice melts.
- **Glimmers (Nether lava)**:
  - Purple-glowing permanent spots placed during Nether world generation that refill over time. Only the **Obsidian Pan** can harvest Glimmers.
- With **Jade** installed, aiming at any panning spot shows whether it is temporary or permanent, its remaining uses, and its despawn or next-refill timer.

### Panning Loot

- **River Panning**:
  - Always drops **3–6 clay balls** plus sand, gravel, or flint.
  - Rolls alluvial ore silt (iron nuggets, raw copper, coal, gold nuggets) and finds (Ancient Coins, nautilus shells, Lost Pages, plus Sheaf/Friend sherds in regular rivers or Burn/Howl sherds in frozen rivers).
  - **Riverbed Minerals** (lapis lazuli, amethyst shards, emerald, diamond): Scales strongly with luck—modest with a Copper Pan, much higher with a Gold Pan, disabled on Obsidian Pan.
  - **High-Luck Relics**: Reaching **`+1.0` total luck** or more (such as using a Gold Pan or Luck effects) unlocks bonus Ancient Coins, Lost Pages, Bottles o' Enchanting, name tags, Heart/Heartbreak/Danger sherds, and golden apples.
- **Nether Lava Panning**:
  - Consistently yields basalt, blackstone, gilded blackstone, magma cream, soul sand, and soul soil, alongside gold nuggets, quartz, glowstone dust, obsidian, and crying obsidian (handy for repairing your Obsidian Pan on the go).
  - Can also yield blaze powder, ghast tears, fire charges, **netherite scrap**, and biome-specific Nether drops (ender pearls in Warped Forests, nether wart in Crimson Forests, bone blocks in Soul Sand Valleys, gilded blackstone in Basalt Deltas, and gold ingots in Nether Wastes).
