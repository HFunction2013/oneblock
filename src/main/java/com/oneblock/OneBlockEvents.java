package com.oneblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.List;
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
 *  - /oneblock:refreshzone re-rolls the current chunk with a non-seed RNG that
 *    is seeded once with time(NULL) when the server starts.
 */
public class OneBlockEvents {

    /** Chunk persistent-data key holding the chosen block. */
    public static final String KEY_BLOCK = "oneblock.block";

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

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Seed the refresh RNG with the world's start time (time(NULL)).
        refreshRandom = new Random(System.currentTimeMillis() / 1000L);
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return; // The server is authoritative for the replacement.
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return; // Chunk is not fully generated yet; the full Load follows.
        }

        CompoundTag data = chunk.getPersistentData();
        if (data.contains(KEY_BLOCK)) {
            return; // Already processed on a previous load; the saved state is final.
        }

        Block block = pickBlockForChunk(((ServerLevel) level).getSeed(),
                chunk.getPos().x, chunk.getPos().z);
        data.putString(KEY_BLOCK, keyOf(block));
        chunk.setUnsaved(true);

        replaceChunkBlocks(chunk, block);
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

    /**
     * Rewrites every block of the chunk to the target block.
     * Air, bedrock, barriers and blocks that already match are left alone, so
     * the terrain and building shapes are preserved and only the material
     * changes. Block entities of replaced blocks are removed.
     */
    public static void replaceChunkBlocks(LevelChunk chunk, Block block) {
        BlockState target = block.defaultBlockState();
        Level level = chunk.getLevel();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        // Drop tile entities whose blocks are about to be replaced.
        Set<BlockPos> blockEntityPositions = new HashSet<>(chunk.getBlockEntitiesPositions());
        for (BlockPos pos : blockEntityPositions) {
            if (chunk.getBlockState(pos).getBlock() != block) {
                chunk.removeBlockEntity(pos);
            }
        }

        // Fast pass over the whole chunk: no neighbor updates, no relight, no
        // client packets (the chunk is still being built when this runs).
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block current = chunk.getBlockState(pos).getBlock();
                    if (current == Blocks.AIR
                            || current == Blocks.BEDROCK
                            || current == Blocks.BARRIER
                            || current == block) {
                        continue;
                    }
                    chunk.setBlockState(pos, target, false);
                }
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
