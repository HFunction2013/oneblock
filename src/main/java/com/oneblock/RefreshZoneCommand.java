package com.oneblock;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Implements /oneblock:refreshzone
 *
 * Re-rolls the block of the chunk the player is currently standing in with the
 * session RNG (seeded from time(NULL) when the server started), NOT from the
 * world seed. The refreshed choice is stored in the chunk data and survives
 * saving and reloading the world.
 */
public class RefreshZoneCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("oneblock:refreshzone")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            LevelChunk chunk = player.serverLevel()
                                    .getChunk(player.chunkPosition().x, player.chunkPosition().z);

                            Random random = OneBlockEvents.getRefreshRandom();
                            Block block = OneBlockEvents.BLOCKS
                                    .get(random.nextInt(OneBlockEvents.BLOCKS.size()));

                            ChunkPos pos = chunk.getPos();
                            OneBlockEvents.applyOverride(pos, block);
                            OneBlockEvents.replaceChunkBlocks(chunk, block);

                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Refreshed chunk [" + pos.x + ", " + pos.z
                                            + "] as " + block.getName().getString() + "."), true);
                            return 1;
                        }));
    }
}
