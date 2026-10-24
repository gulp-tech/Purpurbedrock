package org.purpurmc.purpur.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.Bukkit;
import org.purpurmc.purpur.PurpurConfig;

/**
 * Eliminates Bedrock ghost blocks ("фантомные блоки") on fast mining, cancelled WorldGuard/spawn
 * breaks & placements, rate-limited bridging packets, and wall-clipping collisions.
 */
public final class BedrockGhostBlockResync {
    private BedrockGhostBlockResync() {
    }

    public static void resyncNeighborhood(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!PurpurConfig.bedrockPreventGhostBlocks || player == null || player.connection == null || level == null || pos == null) {
            return;
        }
        sendBlockAndNeighbors(player, level, pos);
        scheduleNextTickConfirmation(player, level, pos, false);
    }

    public static void resyncPlacement(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        if (!PurpurConfig.bedrockPreventGhostBlocks || player == null || player.connection == null || packet == null) {
            return;
        }
        ServerLevel level = player.level();
        BlockHitResult hit = packet.getHitResult();
        BlockPos clickedPos = hit.getBlockPos();
        BlockPos placedPos = clickedPos.relative(hit.getDirection());
        sendSingleIfLoaded(player, level, clickedPos);
        sendBlockAndNeighbors(player, level, placedPos);
        player.inventoryMenu.broadcastFullState();
        scheduleNextTickConfirmation(player, level, placedPos, true);
    }

    public static void resyncCollisionBox(ServerPlayer player, double targetX, double targetY, double targetZ) {
        if (!PurpurConfig.bedrockPreventGhostBlocks || player == null || player.connection == null) {
            return;
        }
        ServerLevel level = player.level();
        int minX = (int) Math.floor(targetX - 0.8D);
        int maxX = (int) Math.floor(targetX + 0.8D);
        int minY = (int) Math.floor(targetY - 0.5D);
        int maxY = (int) Math.floor(targetY + 2.2D);
        int minZ = (int) Math.floor(targetZ - 0.8D);
        int maxZ = (int) Math.floor(targetZ + 0.8D);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    sendSingleIfLoaded(player, level, new BlockPos(x, y, z));
                }
            }
        }
    }

    private static void sendBlockAndNeighbors(ServerPlayer player, ServerLevel level, BlockPos pos) {
        sendSingleIfLoaded(player, level, pos);
        for (Direction dir : Direction.values()) {
            sendSingleIfLoaded(player, level, pos.relative(dir));
        }
    }

    private static void sendSingleIfLoaded(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (level.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return;
        }
        player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && be.getUpdatePacket() != null) {
            player.connection.send(be.getUpdatePacket());
        }
    }

    private static void scheduleNextTickConfirmation(
        ServerPlayer player,
        ServerLevel level,
        BlockPos pos,
        boolean syncInventory
    ) {
        try {
            level.getServer().processQueue.add(() -> {
                if (player.connection == null || player.isRemoved() || player.level() != level) {
                    return;
                }
                sendBlockAndNeighbors(player, level, pos);
                if (syncInventory) {
                    player.inventoryMenu.broadcastFullState();
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
