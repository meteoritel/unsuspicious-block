# Unsuspicious Block

**Unsuspicious Block** is a Minecraft 1.21.1 multi-loader archaeology enhancement mod that lets you quickly reveal the loot hidden inside suspicious blocks without long brushing sessions, while recording every excavation in the **Archaeology Journal**.

> **中文版**: [README](../../README.md)

---

## Features

### Archaeology Journal

The **Archaeology Journal** is the core feature of this mod. It automatically records all of your archaeology discoveries.

#### Crafting Recipe

| <img src="../image/readme/archaeology_journal_recipe.png" alt="Archaeology Journal crafting recipe" width="480"> |
|:----------------------------------------------------------------------------------------------------------------:|
|                                       Archaeology Journal crafting recipe                                        |

#### Interface Preview

The Archaeology Journal automatically scans all archaeology loot tables in the game, displays them by structure name, and is also compatible with loot tables added by other mods.

| <img src="../image/readme/archaeology_journal_GUI1.png" alt="Archaeology Journal catalog screen" width="360"> | <img src="../image/readme/archaeology_journal_GUI2.png" alt="Archaeology Journal item details screen" width="360"> |
|:-------------------------------------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------------------------------:|
|                                              Loot table catalog                                               |                                                 Item details page                                                  |

#### Unlock Status

Each time you brush out a new item, the Archaeology Journal automatically unlocks the corresponding entry and records it.

When no entries have been unlocked yet, the interface looks like this:

| <img src="../image/readme/archaeology_journal_unlock1.png" alt="No loot tables unlocked" width="360"> | <img src="../image/readme/archaeology_journal_unlock2.png" alt="No items unlocked" width="360"> |
|:-----------------------------------------------------------------------------------------------------:|:-----------------------------------------------------------------------------------------------:|
|                                        No loot tables unlocked                                        |                                        No items unlocked                                        |

### Suspicious Analyzer

The **Suspicious Analyzer** reveals the hidden loot inside any suspicious block with a right-click.

| <img src="../image/readme/jade_plugin.png" alt="Jade integration preview" width="480"> |
|:--------------------------------------------------------------------------------------:|
|                                Jade integration preview                                |

- Right-click suspicious sand or suspicious gravel to immediately reveal the contained item
- Automatically sync the result to the Archaeology Journal
- Compatible with all block entities derived from `BrushableBlockEntity`, including suspicious blocks added by other mods
- When used with Jade, the scanned item information can be shown directly in the HUD

---

## License

This mod is licensed under the **MIT License**. Feel free to use it in modpacks.
