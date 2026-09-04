# Suspicious Reader & Archaeological Shovel

These two tools form a "scan first, excavate later" archaeology workflow: the Suspicious Reader finds where suspicious blocks are, and the Archaeological Shovel quickly digs out suspicious blocks and extracts their loot. Each tool has its own priority when held in the main hand or offhand, ensuring a scan always happens before extraction.

## Suspicious Reader

### Crafting

The recipe requires:

- 2 Ancient Coins
- 1 glass pane
- 2 iron ingots
- 1 ender pearl
- 2 redstone
- 1 quartz

We recommend using JEI, REI, or EMI to view the exact recipe in your modpack.

### Single-Block Scan

Set the scan level to "Single Block" and right-click a suspicious block to view its loot. Single-block scans are free and consume no Reader energy.

Scanning only reads the contents; afterwards you can right-click with the Archaeological Shovel to excavate quickly.

### Ranged Scan

While holding the Reader, press `V` to cycle scan levels:

| Mode | Range | Notes |
|---|---:|---|
| Single Block | 1 block | Free read of the targeted block |
| Low Range | 3×3×3 | Base cost 1 level of energy, plus extra charge for new suspicious blocks |
| Mid Range | 5×5×5 | Base cost 2 levels of energy |
| High Range | 7×7×7 | Base cost 3 levels of energy, suited to sweeping large areas |

A ranged scan highlights suspicious blocks and unopened loot containers in range. Every ranged scan costs at least the energy for the current level, and each suspicious block parsed for the first time in that scan costs 1 extra energy point.

### Energy and Recharging

- Maximum energy: 512 points
- Each Ancient Coin: restores 256 points
- Sneak and right-click air: consumes 1 Ancient Coin from your inventory to recharge

When the consumed energy is less than 256 points (i.e. the remaining space cannot absorb a full coin's charge), a manual recharge warns you that energy may be wasted; repeating the same action within the warning window force-consumes the coin and completes the recharge.

## Archaeological Shovel

### Crafting and Base Stats

The Archaeological Shovel requires 3 iron ingots, 1 copper ingot, and 1 stick. It has the mining tier of iron tools and 256 durability.

### Direct Excavation

Right-click a suspicious block that has been scanned by **the same player** with the Archaeological Shovel to extract its loot directly. If there is no scan record, if it was scanned by another player, or if the block is already empty, the shovel will not extract the contents.

### Vein Digging

When digging shovel-type blocks such as sand, red sand, and gravel, the Archaeological Shovel digs down up to 3 blocks in a row. Sneaking restores single-block digging.

Vein digging avoids suspicious blocks and their supporting blocks, so it will never break a suspicious block by accident.

### Protecting Suspicious Blocks

The Archaeological Shovel cannot directly dig up suspicious blocks: mining one normally does nothing and does not trigger vein digging. Only while holding Shift (sneaking) can you actually break a suspicious block, and then only a single block. This prevents accidental destruction of loot blocks that have not been brushed yet.

### Obtaining Ancient Coins

When digging sand, red sand, or gravel, the Archaeological Shovel has a 0.3% chance to drop an extra Ancient Coin. Ancient Coins can also:

- Recharge the Suspicious Reader
- Repair any damageable item on an anvil, restoring 25% of maximum durability per coin
- Be traded with a wandering trader: 1 Ancient Coin for 5 emeralds
