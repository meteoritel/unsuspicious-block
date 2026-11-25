# Compatibility and Settings

This page covers optional mod integrations, default keybindings, and configurable settings in Unsuspicious Block `1.6.0`.

## Optional Mod Integrations

### Recipe Viewers

- **JEI**: Shows all crafting recipes, the dedicated Decorated Pottery Wheel category, and furnace firing recipes for unfired pots and sherds. Press `U` / `R` on unlocked items in the Archaeology Journal for uses/recipes, or hover any item in your inventory or JEI and press `C` to search it in the journal.
- **REI / EMI**: Displays standard crafting and smelting recipes.

### Jade

With **Jade** installed, looking at the following targets shows extra info:

- **Suspicious Blocks**: Displays the loot inside blocks you have already scanned with the Suspicious Reader (unscanned blocks stay hidden).
- **Unsuspicious Blocks**: Displays the sealed item and which player sealed it.
- **Panning Spots**: Displays whether the spot is temporary or permanent and its remaining uses; if Jade is also installed on the server, shows the despawn timer (temporary) or next refill timer (permanent).

### Accessory Slots & Artifacts

- **Trinkets** (Fabric) or **Curios** (NeoForge): Adds accessory slots for the [Archaeology Journal](en-Archaeology-Journal) and [Specimen Box](en-Specimen-Box), and a Charm slot for the [Eye of Cat](en-Eye-of-Cat).
- **Artifacts**: Compatible artifacts stored inside a carried [Specimen Box](en-Specimen-Box) retain their equipped effects.

### Pottery Firing Integrations (NeoForge Only)

- **Mekanism** (`10.7.19+`, verified on `10.7.19.85`): **Energized Smelters** can fire unfired sherds and pots while preserving their patterns.
- **Create** (`6.0.10`): **Encased Fans** can bulk-smelt unfired sherds and pots while preserving their patterns.

### Relics

With **Relics** installed, open-water Mud Dredging adds a 2% chance to drop a Relics item. Additionally, `relics:data` is excluded from Archaeology Journal item signatures by default so randomized relic rolls do not clutter your catalog as separate variants.

### Lootr (NeoForge Only)

- Each player's Archaeology Journal tracks only the loot that player personally collects.
- With Lootr team-shared inventories enabled, teammates share container loot while each player records their own pickups.
- The Archaeological Shovel extracts from the specific Lootr inventory bound when you scanned the block; scan again if Lootr recreates that inventory.

## Default Keybindings

All keybinds can be changed under **Options → Controls → Key Binds → Unsuspicious Block**:

| Key | Action |
|---|---|
| `C` | Open a carried Archaeology Journal, or search a hovered inventory/JEI item |
| `V` | Cycle Suspicious Reader scan level (Single Block / 3×3×3 / 5×5×5 / 7×7×7) |
| `H` | Toggle Suspicious Reader HUD panels |

## In-Game Configuration & File Locations

### 1. World Loot Tracking Rules

Stored per world in `<world>/serverconfig/unsuspiciousblock-loot-tracking.json` (shared between Fabric and NeoForge).

- **How to Edit**: Open **Manage tracked loot tables → World Rules** in the Archaeology Journal, or use the Fabric ModMenu / NeoForge Mods config screen. Requires OP level 2 on dedicated servers or LAN worlds; single-player hosts can edit freely.
- **Adjustable Settings**:
  - Enable/disable built-in rules, add prefix or exact table selectors, or exclude specific root tables
  - Log limit per table: default `512` (range `64–4096`)
  - Container tracking timeout: default `6000` ticks (5 minutes; range `600–60000`)
  - Signature-excluded components (defaults to `relics:data`)
- You can also edit the JSON file directly and run `/reload` in-game.

### 2. Supplemental Table Names

Custom table names edited or imported via **Manage tracked loot tables → Manage Table Names** are saved per language in `config/unsuspiciousblock/loot_table_lang/<lang>.json` and shared across worlds on the same server instance.

### 3. Panning Settings

- **Fabric**: Global file at `config/unsuspiciousblock/panning.json`.
- **NeoForge**: Per-world file at `<world>/serverconfig/unsuspiciousblock-panning-server.toml` (editable in-game via the NeoForge Mods config screen in single-player).
- **Default Values**:
  - Natural spawn interval: `600` ticks (30s), cap per dimension: `5`, minimum spacing: `32` blocks
  - Natural spot lifetime: `24000–48000` ticks (20–40 min), depleted `3×3` chunk cooldown: `36000` ticks (30 min)
  - Panning hold duration: `100` ticks (5s), uses per spot: `3`
  - Gold Pan luck bonus: `+1.0`, Gold Pan regeneration chance: `0.10` (10%), Obsidian Pan water luck penalty: `1.0`

## Multiplayer Notes

Both the client and the server must run the same version of the mod. The archaeology catalog and world rules are controlled by the server, while each player's unlock progress and excavation logs are stored per server and do not carry between different servers.
