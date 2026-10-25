package org.purpurmc.purpur.bedrock;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.purpurmc.purpur.PurpurConfig;

import java.lang.reflect.Method;

/**
 * Guarantees reliable Bedrock chunk streaming across initial login, standing still,
 * fast flight, teleports (/tp, /spawn, /warp, /rtp, AuthMe), and dimension switches.
 */
public final class BedrockChunkHelper {
    private BedrockChunkHelper() {
    }

    /**
     * Applies Bedrock-tuned Moonrise chunk streaming parameters on {@link GlobalConfiguration},
     * including on existing servers upgrading with an already-generated {@code paper-global.yml}.
     */
    public static void applyRuntimeChunkTuning() {
        if (!PurpurConfig.bedrockChunkLoadingFix) {
            return;
        }
        GlobalConfiguration global = GlobalConfiguration.get();
        if (global == null) {
            return;
        }
        if (global.chunkLoadingAdvanced != null) {
            // Prevent mobile Bedrock's tiny initial RequestChunkRadius from clamping Moonrise send distance
            global.chunkLoadingAdvanced.autoConfigSendDistance = false;
            if (global.chunkLoadingAdvanced.playerMaxConcurrentChunkLoads == 0) {
                global.chunkLoadingAdvanced.playerMaxConcurrentChunkLoads = 8;
            }
        }
        if (global.chunkLoadingBasic != null) {
            if (global.chunkLoadingBasic.playerMaxChunkSendRate > 0.0
                && global.chunkLoadingBasic.playerMaxChunkSendRate < 120.0) {
                global.chunkLoadingBasic.playerMaxChunkSendRate = 120.0;
            }
            if (global.chunkLoadingBasic.playerMaxChunkLoadRate > 0.0
                && global.chunkLoadingBasic.playerMaxChunkLoadRate < 200.0) {
                global.chunkLoadingBasic.playerMaxChunkLoadRate = 200.0;
            }
        }
    }

    /**
     * Synchronizes chunk cache radius and center immediately after a teleport or dimension change
     * so Bedrock's {@code NetworkChunkPublisherUpdatePacket} never lags behind Moonrise chunk packets.
     */
    public static void onTeleportOrWorldChange(ServerPlayer player) {
        if (!PurpurConfig.bedrockChunkLoadingFix || player == null || player.connection == null) {
            return;
        }
        if (!BedrockCombatHelper.isBedrockPlayer(player)) {
            return;
        }
        sendChunkPublisherSyncPackets(player);
        try {
            player.level().getServer().processQueue.add(() -> {
                if (player.connection != null && !player.isRemoved()) {
                    sendChunkPublisherSyncPackets(player);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static int forceResyncPlayerChunks(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return 0;
        }
        int viewDist = sendChunkPublisherSyncPackets(player);
        try {
            Object session = BedrockPlaceholderHook.findGeyserSession(player.getUUID());
            Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
            if (session != null && geyserPlugin != null) {
                ClassLoader cl = geyserPlugin.getClass().getClassLoader();
                Class<?> vec3iClass = Class.forName("org.cloudburstmc.math.vector.Vector3i", true, cl);
                Class<?> chunkUtilsClass = Class.forName("org.geysermc.geyser.util.ChunkUtils", true, cl);
                Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, cl);
                Method fromXYZ = vec3iClass.getMethod("from", int.class, int.class, int.class);
                Method forceUpdate = chunkUtilsClass.getMethod("forceUpdateChunkPosition", sessionClass, vec3iClass);
                Method ensureLoop = sessionClass.getMethod("ensureInEventLoop", Runnable.class);

                int bx = player.getBlockX();
                int by = player.getBlockY();
                int bz = player.getBlockZ();
                Runnable task = () -> {
                    try {
                        Object pos = fromXYZ.invoke(null, bx, by, bz);
                        forceUpdate.invoke(null, session, pos);
                    } catch (Throwable ignored) {
                    }
                };
                ensureLoop.invoke(session, task);
            }
        } catch (Throwable ignored) {
        }
        return viewDist;
    }

    private static int sendChunkPublisherSyncPackets(ServerPlayer player) {
        int viewDist = Math.max(8, player.level().getWorld().getViewDistance());
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(viewDist));
        player.connection.send(new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
        return viewDist;
    }
}
