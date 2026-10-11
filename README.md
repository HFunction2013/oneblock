# One Block

A Minecraft Forge 1.20.1 (47.3.0) mod where **every chunk is made of a single block type**.

## Features

- Each chunk is rewritten to **one block type** after terrain generation completes.
- The block is chosen **deterministically from the world seed**: the same seed always produces the same block layout (computed from `seed + chunkX + chunkZ`).
- The bottom **bedrock layer is preserved**.
- **Terrain keeps its exact shape**: air pockets stay air, so caves, lakes, village houses and other structures are not destroyed — only the material changes (a village house becomes a house made of the chunk's block, still walkable inside).
- `/oneblock:refreshzone` re-rolls the chunk you are standing in with a **non-seed RNG** (seeded once with `time(NULL)` when the server starts), so refreshed zones do *not* follow the world seed.

## Command

```
/oneblock:refreshzone
```

Re-rolls the current chunk's block with the session random source and immediately rewrites the chunk. The refreshed choice is stored in the chunk, so it survives saving and reloading the world.

## Performance notes

- Replacement runs **once** per chunk, on the first full chunk load, after world generation and structure placement are done.
- It uses fast section-level writes with **no neighbor block updates, no lighting recalculation and no per-block client packets** (the chunk is still being built/sent when the pass runs), so the world generation cost stays low.
- Block entities (chests, signs, furnaces...) of replaced blocks are removed to avoid ghost tile entities.

## Build

Requires JDK 17.

```
./gradlew build
```

The jar lands in `build/libs/` and can be dropped into `mods/`.

## Notes

- The candidate pool is a curated list of ~80 solid blocks (no air, fluids, bedrock, barriers or tile-entity blocks).
- Fluids are treated like terrain blocks and are replaced, keeping the strict "one block per chunk" rule.
