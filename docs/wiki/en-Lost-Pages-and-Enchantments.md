# Lost Pages and Enchantments

This mod adds four enchantments focused on fishing, shearing, brushing, and fossil mining, along with a Smithing Table recipe that turns Lost Pages into enchanted books.

## Obtaining Lost Pages

Lost Pages can be found from:

- **Trail Ruins**: 8% chance in the rare archaeology loot pool.
- **River Panning**: Dropped when panning **Shimmers** with a [Copper or Gold Pan](en-Fishing-and-Panning) (and available in the high-luck relic pool at `+1.0` total luck).
- **Fossil Hunter**: Chance to drop when mining natural bone blocks with the Fossil Hunter enchantment.

## Crafting Base Pages

Place **3 Pitcher Plants** in a horizontal row on a crafting table to craft **3 Base Pages**. A single Base Page can also be crafted back into 1 piece of paper.

![Three pitcher plants craft into three Base Pages](https://raw.githubusercontent.com/meteoritel/unsuspicious-block/1.21.1/docs/image/wiki/en-recipe-base-page.png)

![One Base Page crafts into one piece of paper](https://raw.githubusercontent.com/meteoritel/unsuspicious-block/1.21.1/docs/image/wiki/en-recipe-base-page-to-paper.png)

## Forging a Random Enchanted Book

Place the following items into a **Smithing Table** from left to right:

1. **Base Page** (Template slot)
2. **Book** (Base slot)
3. **Lost Page** (Addition slot)

![Forging a random enchanted book with a Base Page, book, and Lost Page](https://raw.githubusercontent.com/meteoritel/unsuspicious-block/1.21.1/docs/image/wiki/en-recipe-enchanted-book.png)

Taking the output rolls an enchanted book with one of three outcomes:

- **20% chance**: A mod enchantment only (random level)
- **30% chance**: A mod enchantment plus vanilla enchantments (rolled at experience level 10–20)
- **50% chance**: Vanilla enchantments only (rolled at experience level 15–35)

## The Four Enchantments

| Enchantment | Max Level | Applies To | Sources | Effect |
|---|---|---|---|---|
| **Mud Dredging** | III | Fishing Rod | Lost Page, Enchanting Table, Villager Trade | Adds a chance to dredge 1 bonus drop per catch: 20% at Level I, +10% per extra level (40% at III). Drops vary by open water and biome (see [Fishing and Panning](en-Fishing-and-Panning)). |
| **Textile Recovery** | I | Shears | Lost Page, Villager Trade, Anvil | Shearing sheep always drops 1–3 extra string. |
| **Precision Excavation** | III | Brush | Lost Page, Villager Trade, Anvil | Brushing a suspicious block has a **28% / 44% / 60%** chance to double that brush's loot. |
| **Fossil Hunter** | I | Pickaxe | Lost Page, Random Loot Enchantments (Treasure) | Breaking naturally generated bone blocks has a **50%** chance to trigger a dimension-specific fossil loot roll. |

### Fossil Hunter Details

- **Natural Bone Blocks Only**: Only bone blocks generated with chunks or structures qualify. Player-placed or machine-placed bone blocks, creative mode breaks, worlds with `doTileDrops` disabled, or previously harvested positions do not trigger bonus loot.
- **Dimension Tables & Fortune**:
  - **Overworld**: Drops bones, bone meal, flint, coal, iron ingots, copper ingots, redstone, amethyst shards, plus chances for Ancient Coins (55%), Lost Pages (20%), and Artifacts (10% when Artifacts is installed).
  - **Nether**: Drops bones, bone meal, quartz, gold nuggets, glowstone dust, blaze powder, magma cream, gold ingots (40%), Ancient Coins (35%), Lost Pages (15%), Artifacts (10%), and Netherite Scrap (8%).
  - Ore-like drops and Netherite Scrap scale with **Fortune** on your pickaxe.

### Viewing Enchantment Candidates

Carry an [Eye of Cat](en-Eye-of-Cat) to view the full list of candidate enchantments in the Enchanting Table before spending levels.
