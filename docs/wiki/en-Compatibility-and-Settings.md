# Compatibility and Settings

This page only covers what players need to know when installing other mods or joining multiplayer servers. All integrations are optional.

## Recipe Viewers

- JEI provides this mod's recipes plus a dedicated recipe category for the Decorated Pottery Wheel.
- REI and EMI can view regular recipes.

## Jade

With Jade installed, suspicious blocks that the current player has already scanned can display the parsed loot in the block info. Unscanned blocks do not leak their contents through Jade.

## Accessory Slots

With Trinkets (Fabric) or Curios (NeoForge) installed, the Archaeology Journal, Eye of Cat, and Specimen Box can be equipped in the matching accessory slots. Carryable items inside the Specimen Box also keep participating in the mod's carry detection.

## Artifacts

With Artifacts installed, the Specimen Box can proxy the equipment effects of compatible artifacts. Which artifacts can be proxied depends on the current Artifacts version and that artifact's own implementation.

## Lootr

The NeoForge build offers optional Lootr compatibility: different players opening the same Lootr chest have their contents recorded independently.

## Server Settings

Loot tracking settings are saved per world; switching saves does not carry over another world's rules. Server administrators can adjust:

- The loot table matching rules that determine what gets included in the Archaeology Journal
- The log retention cap per loot table: 512 entries by default, adjustable from 64 to 4096
- The loot container tracking timeout: 6000 game ticks by default, i.e. 5 minutes

These settings affect what the Log and Catalog include; they do not change the Suspicious Reader's scan radius or the fixed effects of the four enchantments.

## Multiplayer Notes

The mod must be installed on both the client and the server. The archaeology catalog is provided by the server; personal unlock progress and logs are not shared automatically between different servers.
