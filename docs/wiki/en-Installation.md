# Installation

> For Minecraft 1.21.1 and Unsuspicious Block 1.6.0.

## Requirements

- **Minecraft**: 1.21.1
- **Java**: 21
- **Mod Loader**: Fabric Loader 0.17.0+ or NeoForge 21.1.221+
- **Dependencies**: Fabric requires Fabric API; NeoForge requires no extra mods (Meteo's UI Kit is bundled inside both JARs, so no separate download is needed)

## Single-Player Installation

1. Set up a matching Fabric or NeoForge profile for Minecraft 1.21.1.
2. Place the Unsuspicious Block `.jar` for your loader into the `mods` folder.
3. For Fabric, also place Fabric API into the `mods` folder.
4. Launch Minecraft 1.21.1 and verify the mod appears in your mod list.

## Multiplayer

Both the client and the server must install Unsuspicious Block `1.6.0`. With the mod installed on the client only, journal syncing, Suspicious Reader scanning, direct shovel excavation, and panning spots will not work.

## Optional Integrations

None of the following mods are required:

- **JEI**: View recipes, the dedicated Decorated Pottery Wheel category, and furnace firing recipes for unfired pots and sherds; press `U` / `R` on unlocked items in the Archaeology Journal, or hover any item and press `C` to search it in the journal.
- **REI / EMI**: View regular crafting and smelting recipes.
- **Jade**: Displays loot in suspicious blocks you have scanned, sealed contents in Unsuspicious Blocks, and panning spot types, remaining uses, and refill/despawn timers.
- **Trinkets** (Fabric) or **Curios** (NeoForge): Adds accessory slots for the Archaeology Journal, Specimen Box, and Eye of Cat.
- **Artifacts**: Allows compatible artifacts stored inside a carried Specimen Box to keep their effects active.
- **Mekanism** (NeoForge `10.7.19+`, verified on `10.7.19.85`): Fires unfired sherds and pots in the Energized Smelter while preserving their patterns.
- **Create** (NeoForge `6.0.10`): Bulk-fires unfired sherds and pots with Encased Fans while preserving their patterns.
- **Relics**: Adds a small chance to pull Relics items during open-water Mud Dredging.
- **Lootr** (NeoForge only): Tracks Lootr containers and suspicious blocks per player.

## Upgrading or Switching Loaders

Back up your world before upgrading or switching between Fabric and NeoForge.

- When upgrading from `1.5.2` or earlier to `1.6.0`, **update both the client and the server**.
- World loot-tracking rules are shared between Fabric and NeoForge in `<world>/serverconfig/unsuspiciousblock-loot-tracking.json`. Opening an older world in `1.6.0` for the first time automatically imports legacy rules and log settings; if you plan to switch loaders, open the world once in `1.6.0` using its original loader first so the settings import completes.
- Loader-specific global configs (such as panning settings) and optional integrations are managed separately by each loader. See [Compatibility and Settings](en-Compatibility-and-Settings).
