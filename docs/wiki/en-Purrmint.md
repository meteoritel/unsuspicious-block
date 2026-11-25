# Purrmint

Purrmint is an ancient plant whose mature flowers and potted variants create a protective aura through walls that keeps Creepers and Phantoms away from the area and anyone standing inside it.

## Obtaining and Growing

- **Finding Seeds**: Whenever a Sniffer digs up ancient seeds, there is an extra **50%** chance for it to also uncover 1 **Purrmint Seeds** item (vanilla Torchflower and Pitcher Pod drops are unchanged).
- **Farming**: Plant Purrmint Seeds on farmland. They grow naturally over time, can be fertilized with bone meal, and can be pollinated by bees carrying pollen.
- **Harvesting**: Breaking an immature crop returns the seeds; breaking a fully grown Purrmint drops the mature plant itself, which you can place anywhere or put into a vanilla flower pot as **Potted Purrmint**.

## Protective Area

Mature Purrmint and Potted Purrmint provide the exact same protection (seedlings and empty flower pots have no effect):

- **Area of Effect**: Covers a `17×17×17` cube centered on the plant or pot (8 blocks outward along every axis). The aura **passes through walls**, and overlapping Purrmint plants merge into one continuous protected zone.
- **Repelling Creepers & Phantoms**: Creepers and Phantoms avoid movement destinations and attack targets inside the protected zone, routing around it instead. If one is already chasing you when you step inside, its next pathfinding or flight update drops aggro and cancels swelling or swooping attacks aimed at you.
- **Phantom Spawning Protection**: Natural Phantom spawning skips any player whose feet are inside a Purrmint aura (players outside the zone can still trigger Phantom spawns).

## Practical Limits

- Inside/outside checks use the **block at a player's or mob's feet**, and the aura remains active as long as the chunk containing the Purrmint is loaded.
- Mobs already inside the zone can walk or fly out on their own; the aura does not push mobs out physically.
- The aura governs active mob movement and targeting only—knockback, water currents, or falling can still carry mobs inside, and Creepers manually ignited with flint and steel continue their vanilla fuse.
