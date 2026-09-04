# Installation

> For Minecraft 1.21.1 and Unsuspicious Block 1.5.2.

## Requirements

- Minecraft 1.21.1
- Java 21
- Fabric Loader 0.17.0 or later, or NeoForge 21.1.195 or later
- The Fabric version requires Fabric API; the NeoForge version has no additional required dependencies

## Single-player

1. Install the matching Fabric or NeoForge launcher profile.
2. Put the Unsuspicious Block `.jar` matching your loader into the `mods` folder.
3. Fabric users should also add Fabric API.
4. Launch Minecraft 1.21.1 and confirm the mod is loaded in the main menu or mod list.

## Multiplayer

Both the client and the server must have the mod installed, using the same Minecraft, loader, and mod versions. With the mod installed on the client only, journal data syncing, scanning, and excavation will not work correctly.

## Optional Integrations

The following mods are not required to run this mod:

- **JEI**: View recipes, plus a dedicated recipe category for the Decorated Pottery Wheel.
- **REI / EMI**: Can be used to view regular recipes; this mod does not hard-depend on them.
- **Jade**: View the loot already scanned from suspicious blocks.
- **Trinkets** (Fabric) or **Curios** (NeoForge): Provide accessory slots for the Archaeology Journal, Eye of Cat, and Specimen Box.
- **Artifacts**: Lets the Specimen Box proxy the effects of compatible artifacts.
- **Lootr**: Optional compatibility provided by the NeoForge build.

## Updating or Switching Loaders

When switching between Fabric and NeoForge, keep a backup of your world. Config file locations and optional integrations differ between the two sides; do not copy config files from one loader directly into the other.
