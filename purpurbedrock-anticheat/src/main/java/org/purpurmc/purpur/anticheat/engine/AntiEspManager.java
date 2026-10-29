package org.purpurmc.purpur.anticheat.engine;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Packet-level player ESP protection. A target that remains fully occluded is removed from the
 * viewer's entity stream; it is restored immediately when visible or close enough for normal PvP.
 * Hysteresis prevents entity flicker at doorways and corners.
 */
public final class AntiEspManager {
    private record Pair(UUID viewer, UUID target) {
    }

    private static final class VisibilityState {
        int occludedSamples;
        boolean hidden;
    }

    private final PurpurBedrockACPlugin plugin;
    private final Map<Pair, VisibilityState> states = new HashMap<>();
    private final Map<UUID, Long> readyAfterMs = new HashMap<>();
    private BukkitTask task;

    public AntiEspManager(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Reload must release every hide owned by this plugin before rebuilding state.
        stop(true);
        for (Player player : Bukkit.getOnlinePlayers()) {
            onJoin(player);
        }
        int interval = Math.max(2, plugin.cfg().antiEspIntervalTicks);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop(boolean reveal) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (reveal) {
            revealAll();
        }
        states.clear();
        readyAfterMs.clear();
    }

    public void onJoin(Player player) {
        long graceMs = Math.max(200L, plugin.cfg().antiEspJoinGraceTicks * 50L);
        UUID id = player.getUniqueId();
        readyAfterMs.put(id, System.currentTimeMillis() + graceMs);
        // A reconnect has a fresh server-side player object. Drop stale pair bookkeeping
        // silently: showEntity here would itself emit an unsafe packet during StartGame.
        states.entrySet().removeIf(entry -> entry.getKey().viewer.equals(id)
            || entry.getKey().target.equals(id));
    }

    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        readyAfterMs.remove(id);
        states.entrySet().removeIf(entry -> {
            Pair pair = entry.getKey();
            VisibilityState state = entry.getValue();
            if (!pair.viewer.equals(id) && !pair.target.equals(id)) {
                return false;
            }
            if (state.hidden) {
                Player viewer = Bukkit.getPlayer(pair.viewer);
                Player target = Bukkit.getPlayer(pair.target);
                if (viewer != null && target != null) {
                    viewer.showEntity(plugin, target);
                }
            }
            return true;
        });
    }

    private void tick() {
        ACConfig cfg = plugin.cfg();
        if (!cfg.antiEspEnabled) {
            if (!states.isEmpty()) {
                revealAll();
                states.clear();
            }
            return;
        }

        long nowMs = System.currentTimeMillis();
        readyAfterMs.entrySet().removeIf(entry -> entry.getValue() <= nowMs);
        Set<Pair> visited = new HashSet<>();
        double minSq = cfg.antiEspMinDistance * cfg.antiEspMinDistance;
        double maxSq = cfg.antiEspMaxDistance * cfg.antiEspMaxDistance;

        for (World world : Bukkit.getWorlds()) {
            var players = world.getPlayers();
            if (players.size() < 2) {
                continue;
            }
            for (Player viewer : players) {
                if (!eligibleViewer(viewer, cfg) || !protocolReady(viewer, nowMs)) {
                    revealForViewer(viewer);
                    continue;
                }
                for (Player target : players) {
                    if (viewer == target || !target.isOnline() || target.isDead()) {
                        continue;
                    }
                    double distanceSq = viewer.getLocation().distanceSquared(target.getLocation());
                    Pair pair = new Pair(viewer.getUniqueId(), target.getUniqueId());
                    visited.add(pair);
                    VisibilityState state = states.computeIfAbsent(pair, ignored -> new VisibilityState());

                    boolean mustShow = !protocolReady(target, nowMs)
                        || distanceSq <= minSq
                        || distanceSq > maxSq
                        || viewer.hasLineOfSight(target);
                    if (mustShow) {
                        state.occludedSamples = 0;
                        if (state.hidden) {
                            viewer.showEntity(plugin, target);
                            state.hidden = false;
                        }
                        continue;
                    }

                    state.occludedSamples++;
                    if (!state.hidden && state.occludedSamples >= cfg.antiEspHideAfterSamples) {
                        viewer.hideEntity(plugin, target);
                        state.hidden = true;
                    }
                }
            }
        }

        states.entrySet().removeIf(entry -> {
            if (visited.contains(entry.getKey())) {
                return false;
            }
            VisibilityState state = entry.getValue();
            if (state.hidden) {
                Player viewer = Bukkit.getPlayer(entry.getKey().viewer);
                Player target = Bukkit.getPlayer(entry.getKey().target);
                if (viewer != null && target != null) {
                    viewer.showEntity(plugin, target);
                }
            }
            return true;
        });
    }

    private boolean protocolReady(Player player, long nowMs) {
        return readyAfterMs.getOrDefault(player.getUniqueId(), 0L) <= nowMs;
    }

    private boolean eligibleViewer(Player viewer, ACConfig cfg) {
        if (!viewer.isOnline() || viewer.isDead()) {
            return false;
        }
        GameMode mode = viewer.getGameMode();
        if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
            return false;
        }
        return !(cfg.enableBypassPermission && viewer.hasPermission(cfg.permBypass));
    }

    private void revealForViewer(Player viewer) {
        UUID id = viewer.getUniqueId();
        states.forEach((pair, state) -> {
            if (state.hidden && pair.viewer.equals(id)) {
                Player target = Bukkit.getPlayer(pair.target);
                if (target != null) {
                    viewer.showEntity(plugin, target);
                }
                state.hidden = false;
                state.occludedSamples = 0;
            }
        });
    }

    private void revealAll() {
        states.forEach((pair, state) -> {
            if (!state.hidden) {
                return;
            }
            Player viewer = Bukkit.getPlayer(pair.viewer);
            Player target = Bukkit.getPlayer(pair.target);
            if (viewer != null && target != null) {
                viewer.showEntity(plugin, target);
            }
        });
    }
}
