package com.oneblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

/**
 * Replaces the blocks of every chunk with a single chunk-wide block type.
 *
 *  - Seed-based: the block for a chunk is hashed from (world seed, chunk X, Z),
 *    so the same world seed always produces the same layout.
 *  - Replacement happens once when the chunk is first fully loaded, after
 *    terrain generation and structure placement. Air and bedrock are kept, so
 *    terrain, caves and buildings keep their exact shape.
 *  - Performance: section-level writes, no neighbor updates, no relight and no
 *    per-block client packets, because the chunk is still being sent to clients.
 *  - Throttled: at most {@link #MAX_CHUNKS_PER_TICK} chunks are rewritten per
 *    server tick so exploring new terrain does not stall the main thread.
 *  - /oneblock:refreshzone re-rolls the current chunk with a non-seed RNG that
 *    is seeded once with time(NULL) when the server starts. Refreshed choices
 *    are persisted into the chunk NBT via ChunkDataEvent.
 */
public class OneBlockEvents {

    /** NBT keys written into chunk data on save (via ChunkDataEvent). */
    public static final String NBT_DONE = "oneblock_done";
    public static final String NBT_OVERRIDE = "oneblock_override";

    /** Maximum chunks rewritten in a single server tick (main-thread budget). */
    private static final int MAX_CHUNKS_PER_TICK = 2;

    /** Curated candidate pool (no air, fluids, bedrock, barriers or tile entities). */
    public static final List<Block> BLOCKS = List.of(
            Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.CALCITE, Blocks.TUFF, Blocks.DEEPSLATE,
            Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
            Blocks.BRICKS,
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
            Blocks.SAND, Blocks.RED_SAND, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.GRAVEL, Blocks.CLAY,
            Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA,
            Blocks.MAGENTA_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
            Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA,
            Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA,
            Blocks.BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA,
            Blocks.RED_TERRACOTTA, Blocks.BLACK_TERRACOTTA,
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
            Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS,
            Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS,
            Blocks.OBSIDIAN, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.ICE,
            Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.END_STONE,
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.BOOKSHELF,
            Blocks.HAY_BLOCK, Blocks.MELON, Blocks.PUMPKIN, Blocks.MOSS_BLOCK,
            Blocks.AMETHYST_BLOCK, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK,
            Blocks.EMERALD_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.LAPIS_BLOCK,
            Blocks.COAL_BLOCK, Blocks.COPPER_BLOCK, Blocks.SLIME_BLOCK);

    /** Non-seed RNG for /oneblock:refreshzone, seeded with time(NULL) at server start. */
    private static Random refreshRandom;

    /** Dimension + chunk position, used as a map key so dimensions do not collide. */
    private record ChunkKey(ResourceKey<Level> dimension, ChunkPos pos) {}

    /** A chunk waiting to be rewritten (held by level + pos so unloads can be detected). */
    private record PendingChunk(ServerLevel level, ChunkPos pos, Block block) {}

    /** Chunks whose replacement state is already final (saved or processed this session). */
    private static final Set<ChunkKey> PROCESSED = new HashSet<>();

    /** Chunks currently queued for throttled replacement (prevents duplicate enqueues). */
    private static final Set<ChunkKey> QUEUED = new HashSet<>();

    /** Chunks with a manual /oneblock:refreshzone override (key -> block). */
    private static final Map<ChunkKey, Block> OVERRIDES = new HashMap<>();

    /** FIFO queue of chunks awaiting replacement, drained at {@link #MAX_CHUNKS_PER_TICK}/tick. */
    private static final Queue<PendingChunk> PENDING = new ArrayDeque<>();

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Seed the refresh RNG with the world's start time (time(NULL)).
        refreshRandom = new Random(System.currentTimeMillis() / 1000L);
    }

    @SubscribeEvent
    public void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Level level = (Level) event.getLevel();
        ChunkKey key = new ChunkKey(level.dimension(), event.getChunk().getPos());
        String override = event.getData().getString(NBT_OVERRIDE);
        if (!override.isEmpty()) {
            Block block = blockFromKey(override);
            if (block != null) {
                OVERRIDES.put(key, block);
            }
        }
        if (event.getData().contains(NBT_DONE)) {
            PROCESSED.add(key);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        Level level = (Level) event.getLevel();
        if (level.isClientSide) {
            return; // The server is authoritative for the replacement.
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return; // Chunk is not fully generated yet; the full Load follows.
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ChunkKey key = new ChunkKey(level.dimension(), chunk.getPos());
        if (PROCESSED.contains(key) || QUEUED.contains(key)) {
            return; // Already replaced, saved, or already waiting in the queue.
        }

        Block block = OVERRIDES.getOrDefault(key,
                pickBlockForChunk(serverLevel.getSeed(), key.pos.x, key.pos.z));
        QUEUED.add(key);
        PENDING.add(new PendingChunk(serverLevel, key.pos, block));
    }

    /**
     * Drains the pending queue at a bounded rate so the main thread is never
     * blocked rewriting many chunks at once. Chunks that were unloaded before
     * their turn are skipped (they will be re-enqueued on next load).
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        int budget = MAX_CHUNKS_PER_TICK;
        PendingChunk task;
        while (budget > 0 && (task = PENDING.poll()) != null) {
            ChunkKey key = new ChunkKey(task.level().dimension(), task.pos());
            QUEUED.remove(key);

            LevelChunk chunk = task.level().getChunkSource().getChunkNow(
                    task.pos().x, task.pos().z);
            if (chunk == null) {
                // Chunk was unloaded before we got to it; it will be re-enqueued
                // on its next load (it is not yet in PROCESSED).
                continue;
            }

            PROCESSED.add(key);
            replaceChunkBlocks(chunk, task.block());
            budget--;
        }
    }

    @SubscribeEvent
    public void onChunkDataSave(ChunkDataEvent.Save event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Level level = (Level) event.getLevel();
        ChunkKey key = new ChunkKey(level.dimension(), event.getChunk().getPos());
        if (!PROCESSED.contains(key)) {
            return;
        }
        event.getData().putBoolean(NBT_DONE, true);
        Block override = OVERRIDES.get(key);
        if (override != null) {
            event.getData().putString(NBT_OVERRIDE, keyOf(override));
        }
    }

    /**
     * Deterministically picks the block for a chunk from the world seed.
     * The same (seed, chunkX, chunkZ) triple always yields the same block.
     */
    public static Block pickBlockForChunk(long worldSeed, int chunkX, int chunkZ) {
        long hash = worldSeed * 0x9E3779B97F4A7C15L
                + (long) chunkX * 341873128712L
                + (long) chunkZ * 132897987541L;
        Random random = new Random(hash);
        return BLOCKS.get(random.nextInt(BLOCKS.size()));
    }

    /** Records a manual refresh override so it survives save/reload cycles. */
    public static void applyOverride(Level level, ChunkPos pos, Block block) {
        ChunkKey key = new ChunkKey(level.dimension(), pos);
        OVERRIDES.put(key, block);
        PROCESSED.add(key);
    }

    /**
     * Rewrites every block of the chunk to the target block.
     * Air, bedrock, barriers and blocks that already match are left alone, so
     * the terrain and building shapes are preserved and only the material
     * changes. Block entities of replaced blocks are removed.
     *
     * <p>Instead of calling {@code LevelChunk.setBlockState} once per block
     * (which allocates a BlockPos, updates heightmaps and touches the block
     * entity bookkeeping for every one of the ~98k positions), the blocks are
     * written straight into each section's palette storage. Every candidate
     * block is a full opaque cube, so neither the heightmaps nor the section
     * air/solid counts change when material replaces material — skipping that
     * overhead is safe and makes world creation finish instead of appearing to
     * hang at 100% while the spawn chunks are converted.
     */
    public static void replaceChunkBlocks(LevelChunk chunk, Block block) {
        BlockState target = block.defaultBlockState();

        // Drop tile entities whose blocks are about to be replaced.
        Set<BlockPos> blockEntityPositions = new HashSet<>(chunk.getBlockEntities().keySet());
        for (BlockPos pos : blockEntityPositions) {
            if (chunk.getBlockState(pos).getBlock() != block) {
                chunk.removeBlockEntity(pos);
            }
        }

        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir()) {
                continue; // Nothing to replace in an empty section.
            }
            PalettedContainer<BlockState> states = section.getStates();
            for (int i = 0; i < 4096; i++) {
                int x = i & 15;
                int z = (i >> 4) & 15;
                int y = (i >> 8) & 15;
                Block current = states.get(x, y, z).getBlock();
                if (current == Blocks.AIR
                        || current == Blocks.BEDROCK
                        || current == Blocks.BARRIER
                        || current == block) {
                    continue;
                }
                states.getAndSet(x, y, z, target);
            }
        }
        chunk.setUnsaved(true);
    }

    public static String keyOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static Block blockFromKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR ? null : block;
    }

    public static Random getRefreshRandom() {
        if (refreshRandom == null) {
            // Extremely unlikely, but never return null to callers.
            refreshRandom = new Random(System.currentTimeMillis() / 1000L);
        }
        return refreshRandom;
    }
}
