package org.purpurmc.purpur.bedrock;

import io.papermc.paper.FeatureHooks;
import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.purpurmc.purpur.PurpurConfig;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guarantees reliable Bedrock chunk streaming across initial login, standing still,
 * fast flight, teleports (/tp, /spawn, /warp, /rtp), AuthMe registration/login,
 * and limbo-to-spawn dimension/world switches.
 */
public final class BedrockChunkHelper {
    private static final AtomicBoolean AUTH_LISTENER_REGISTERED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<UUID, Long> LAST_CHUNK_REFRESH_MS = new ConcurrentHashMap<>();
    private static final Set<String> POST_AUTH_AND_TP_COMMANDS = Set.of(
        "reg", "register", "l", "login", "log", "auth", "2fa", "totp",
        "spawn", "hub", "lobby", "rtp", "wild", "warp", "home", "tpaccept", "tpyes", "back"
    );

    private static final ScheduledExecutorService CHUNK_RESYNC_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Purpurbedrock-ChunkResync");
            t.setDaemon(true);
            return t;
        });

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
            // Respect per-device RequestChunkRadius (mobile clamped <= 8) so 3GB phones (Galaxy A07) never OOM
            global.chunkLoadingAdvanced.autoConfigSendDistance = true;
            if (global.chunkLoadingAdvanced.playerMaxConcurrentChunkLoads == 0
                || global.chunkLoadingAdvanced.playerMaxConcurrentChunkLoads > 4) {
                global.chunkLoadingAdvanced.playerMaxConcurrentChunkLoads = 4;
            }
        }
        if (global.chunkLoadingBasic != null) {
            double targetSendRate = PurpurConfig.bedrockMaxChunkSendRate;
            if (global.chunkLoadingBasic.playerMaxChunkSendRate <= 0.0
                || global.chunkLoadingBasic.playerMaxChunkSendRate > targetSendRate
                || global.chunkLoadingBasic.playerMaxChunkSendRate == 120.0
                || global.chunkLoadingBasic.playerMaxChunkSendRate == 45.0) {
                global.chunkLoadingBasic.playerMaxChunkSendRate = targetSendRate;
            }
            if (global.chunkLoadingBasic.playerMaxChunkLoadRate > 0.0
                && global.chunkLoadingBasic.playerMaxChunkLoadRate < 100.0) {
                global.chunkLoadingBasic.playerMaxChunkLoadRate = 100.0;
            }
        }
    }

    /**
     * Synchronizes chunk cache radius, center, Geyser {@code NetworkChunkPublisherUpdatePacket},
     * and nearby loaded chunks immediately after a teleport or world/dimension switch.
     */
    public static void onTeleportOrWorldChange(ServerPlayer player) {
        if (!PurpurConfig.bedrockChunkLoadingFix || player == null || player.connection == null) {
            return;
        }
        ensureBukkitAuthListenerRegistered();
        if (!BedrockCombatHelper.isBedrockPlayer(player)) {
            return;
        }
        sendChunkPublisherSyncPackets(player);
        forceGeyserPublisherUpdate(player);
        try {
            MinecraftServer server = player.level().getServer();
            server.processQueue.add(() -> {
                if (player.connection != null && !player.isRemoved()) {
                    sendChunkPublisherSyncPackets(player);
                    forceGeyserPublisherUpdate(player);
                }
            });
            scheduleDelayedChunkRefresh(server, player.getUUID(), 600L, 2);
            scheduleDelayedChunkRefresh(server, player.getUUID(), 1850L, 2);
        } catch (Throwable ignored) {
        }
    }

    public static int forceResyncPlayerChunks(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return 0;
        }
        int viewDist = sendChunkPublisherSyncPackets(player);
        forceGeyserPublisherUpdate(player);
        refreshNearbyLoadedChunks(player, 3, true);
        return viewDist;
    }

    private static void scheduleDelayedChunkRefresh(MinecraftServer server, UUID playerUuid, long delayMs, int radius) {
        if (server == null || playerUuid == null) {
            return;
        }
        // Spread reconnect storms over 0.4s instead of refreshing every joining player on one tick.
        long jitterMs = Math.floorMod(playerUuid.hashCode(), 401);
        CHUNK_RESYNC_SCHEDULER.schedule(() -> {
            try {
                server.processQueue.add(() -> {
                    ServerPlayer current = server.getPlayerList().getPlayer(playerUuid);
                    if (current == null || current.connection == null || current.isRemoved()) {
                        return;
                    }
                    if (!BedrockCombatHelper.isBedrockPlayer(current)) {
                        return;
                    }
                    sendChunkPublisherSyncPackets(current);
                    forceGeyserPublisherUpdate(current);
                    refreshNearbyLoadedChunks(current, radius, false);
                });
            } catch (Throwable ignored) {
            }
        }, delayMs + jitterMs, TimeUnit.MILLISECONDS);
    }

    private static void forceGeyserPublisherUpdate(ServerPlayer player) {
        try {
            Object session = BedrockPlaceholderHook.findGeyserSession(player.getUUID());
            Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
            if (session != null && geyserPlugin != null) {
                ClassLoader cl = geyserPlugin.getClass().getClassLoader();
                Class<?> chunkUtilsClass = Class.forName("org.geysermc.geyser.util.ChunkUtils", true, cl);
                Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, cl);
                Method forceUpdateBlock = chunkUtilsClass.getMethod(
                    "forceUpdateChunkPositionBlock",
                    sessionClass,
                    int.class,
                    int.class,
                    int.class
                );
                Method ensureLoop = sessionClass.getMethod("ensureInEventLoop", Runnable.class);

                int bx = player.getBlockX();
                int by = player.getBlockY();
                int bz = player.getBlockZ();
                Runnable task = () -> {
                    try {
                        forceUpdateBlock.invoke(null, session, bx, by, bz);
                    } catch (Throwable ignored) {
                    }
                };
                ensureLoop.invoke(session, task);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void refreshNearbyLoadedChunks(ServerPlayer player, int radius, boolean bypassCooldown) {
        if (player == null || player.connection == null || player.isRemoved()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!bypassCooldown) {
            Long prev = LAST_CHUNK_REFRESH_MS.get(player.getUUID());
            if (prev != null && (now - prev) < 750L) {
                return;
            }
        }
        if (LAST_CHUNK_REFRESH_MS.size() > 512) {
            LAST_CHUNK_REFRESH_MS.clear();
        }
        LAST_CHUNK_REFRESH_MS.put(player.getUUID(), now);

        ServerLevel level = player.level();
        int centerX = player.getBlockX() >> 4;
        int centerZ = player.getBlockZ() >> 4;
        List<ServerPlayer> singleTarget = List.of(player);
        int clampedRadius = adaptiveRefreshRadius(player.getBukkitEntity(), radius);

        // Send center chunk first so the ground right beneath the player's feet appears immediately
        LevelChunk centerChunk = level.getChunkIfLoaded(centerX, centerZ);
        if (centerChunk != null) {
            FeatureHooks.sendChunkRefreshPackets(singleTarget, centerChunk);
        }
        for (int r = 1; r <= clampedRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunkIfLoaded(centerX + dx, centerZ + dz);
                    if (chunk != null) {
                        FeatureHooks.sendChunkRefreshPackets(singleTarget, chunk);
                    }
                }
            }
        }
    }

    private static int adaptiveRefreshRadius(Player player, int requestedRadius) {
        int radius = Math.max(1, Math.min(requestedRadius, 3));
        String profile = BedrockPlaceholderHook.analyzePerformanceProfile(player);
        if ("SAFE".equals(profile)) {
            return Math.min(radius, 1);
        }
        if ("BALANCED".equals(profile)) {
            return Math.min(radius, 2);
        }
        return Math.min(radius, 2); // never burst a 7x7 duplicate refresh during login
    }

    private static void ensureBukkitAuthListenerRegistered() {
        if (AUTH_LISTENER_REGISTERED.get()) {
            return;
        }
        Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
            return;
        }
        if (!AUTH_LISTENER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new BedrockAuthAndWorldSyncListener(), geyserPlugin);
    }

    private static int sendChunkPublisherSyncPackets(ServerPlayer player) {
        int worldDist = Math.max(6, player.level().getWorld().getViewDistance());
        Player bukkitPlayer = player.getBukkitEntity();
        int clientDist = bukkitPlayer.getClientViewDistance();
        int requestedDist = clientDist > 0 ? Math.min(worldDist, Math.max(5, clientDist)) : worldDist;
        String performance = BedrockPlaceholderHook.analyzePerformanceProfile(bukkitPlayer);
        int deviceCap = switch (performance) {
            case "SAFE" -> 6;
            case "BALANCED" -> 8;
            default -> 10;
        };
        int viewDist = Math.max(5, Math.min(requestedDist, deviceCap));
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(viewDist));
        player.connection.send(new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
        return viewDist;
    }

    private static final class BedrockAuthAndWorldSyncListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            triggerFollowUp(event.getPlayer(), 750L, 2200L);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldChange(PlayerChangedWorldEvent event) {
            triggerFollowUp(event.getPlayer(), 550L, 1750L);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRespawn(PlayerRespawnEvent event) {
            triggerFollowUp(event.getPlayer(), 550L, 1750L);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCommand(PlayerCommandPreprocessEvent event) {
            String msg = event.getMessage();
            if (msg == null || msg.length() < 2 || msg.charAt(0) != '/') {
                return;
            }
            int spaceIdx = msg.indexOf(' ');
            String rawCmd = (spaceIdx > 1 ? msg.substring(1, spaceIdx) : msg.substring(1))
                .toLowerCase(Locale.ROOT);
            int colonIdx = rawCmd.indexOf(':');
            if (colonIdx >= 0 && colonIdx + 1 < rawCmd.length()) {
                rawCmd = rawCmd.substring(colonIdx + 1);
            }
            if (POST_AUTH_AND_TP_COMMANDS.contains(rawCmd)) {
                triggerFollowUp(event.getPlayer(), 600L, 1850L);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            LAST_CHUNK_REFRESH_MS.remove(event.getPlayer().getUniqueId());
        }

        private void triggerFollowUp(Player bukkitPlayer, long firstDelayMs, long secondDelayMs) {
            if (!PurpurConfig.bedrockChunkLoadingFix || !(bukkitPlayer instanceof CraftPlayer craftPlayer)) {
                return;
            }
            ServerPlayer handle = craftPlayer.getHandle();
            if (!BedrockCombatHelper.isBedrockPlayer(handle)) {
                return;
            }
            MinecraftServer server = handle.level().getServer();
            scheduleDelayedChunkRefresh(server, handle.getUUID(), firstDelayMs, 2);
            scheduleDelayedChunkRefresh(server, handle.getUUID(), secondDelayMs, 3);
        }
    }
}
