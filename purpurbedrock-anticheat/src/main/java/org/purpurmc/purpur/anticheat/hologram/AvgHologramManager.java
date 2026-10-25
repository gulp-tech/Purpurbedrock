package org.purpurmc.purpur.anticheat.hologram;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;
import org.purpurmc.purpur.anticheat.util.ColorUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-lag, zero-hitbox AVG floating hologram above players.
 *
 * - Visible ONLY to staff who hold {@code purpurac.hologram} and toggled it ON.
 * - Uses {@code setVisibleByDefault(false)} + {@code setPersistent(false)} before spawn:
 *   regular players never receive spawn packets, PvP hitboxes are 100% untouched on Bedrock,
 *   and when no staff has holograms enabled, ZERO entities and ZERO tasks run!
 */
public final class AvgHologramManager {
    private final PurpurBedrockACPlugin plugin;
    private final Set<UUID> activeViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TextDisplay> targetDisplays = new HashMap<>();
    private BukkitTask updateTask;

    public AvgHologramManager(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public void onPlayerJoin(Player player) {
        ACConfig cfg = plugin.cfg();
        if (cfg.hologramEnabled && cfg.hologramAutoEnableOnJoin && player.hasPermission(cfg.permHologram)) {
            activeViewers.add(player.getUniqueId());
            ensureTaskState();
        }
    }

    public void onPlayerQuit(Player player) {
        UUID id = player.getUniqueId();
        activeViewers.remove(id);
        removeTargetDisplay(id);
        if (activeViewers.isEmpty()) {
            shutdownAllDisplays();
        }
    }

    public boolean isEnabledFor(Player viewer) {
        ACConfig cfg = plugin.cfg();
        return cfg.hologramEnabled && viewer.hasPermission(cfg.permHologram) && activeViewers.contains(viewer.getUniqueId());
    }

    public boolean toggle(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (activeViewers.contains(id)) {
            activeViewers.remove(id);
            hideAllFromViewer(viewer);
            if (activeViewers.isEmpty()) {
                shutdownAllDisplays();
            }
            return false;
        }
        activeViewers.add(id);
        ensureTaskState();
        tickHolograms();
        return true;
    }

    public int getActiveViewerCount() {
        return activeViewers.size();
    }

    public int getActiveEntityCount() {
        return targetDisplays.size();
    }

    public void reload() {
        shutdownAllDisplays();
        if (!activeViewers.isEmpty() && plugin.cfg().hologramEnabled) {
            ensureTaskState();
        }
    }

    public void shutdownAllDisplays() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (TextDisplay display : targetDisplays.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        targetDisplays.clear();
    }

    private void hideAllFromViewer(Player viewer) {
        for (TextDisplay display : targetDisplays.values()) {
            if (display != null && display.isValid()) {
                viewer.hideEntity(plugin, display);
            }
        }
    }

    private void removeTargetDisplay(UUID targetId) {
        TextDisplay removed = targetDisplays.remove(targetId);
        if (removed != null && removed.isValid()) {
            removed.remove();
        }
    }

    private void ensureTaskState() {
        ACConfig cfg = plugin.cfg();
        if (!cfg.hologramEnabled || activeViewers.isEmpty()) {
            shutdownAllDisplays();
            return;
        }
        if (updateTask == null) {
            int period = Math.max(4, cfg.hologramUpdateTicks);
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHolograms, 2L, period);
        }
    }

    private void tickHolograms() {
        ACConfig cfg = plugin.cfg();
        if (!cfg.hologramEnabled || activeViewers.isEmpty()) {
            shutdownAllDisplays();
            return;
        }

        // Validate online staff viewers who still have permission
        Iterator<UUID> viewerIt = activeViewers.iterator();
        while (viewerIt.hasNext()) {
            UUID vid = viewerIt.next();
            Player vp = Bukkit.getPlayer(vid);
            if (vp == null || !vp.isOnline() || !vp.hasPermission(cfg.permHologram)) {
                viewerIt.remove();
                if (vp != null) {
                    hideAllFromViewer(vp);
                }
            }
        }
        if (activeViewers.isEmpty()) {
            shutdownAllDisplays();
            return;
        }

        // Clean up displays for targets who went offline or died
        Iterator<Map.Entry<UUID, TextDisplay>> dispIt = targetDisplays.entrySet().iterator();
        while (dispIt.hasNext()) {
            Map.Entry<UUID, TextDisplay> en = dispIt.next();
            Player target = Bukkit.getPlayer(en.getKey());
            TextDisplay td = en.getValue();
            if (target == null || !target.isOnline() || target.isDead() || td == null || !td.isValid()
                || td.getWorld() != target.getWorld()) {
                if (td != null && td.isValid()) {
                    td.remove();
                }
                dispIt.remove();
            }
        }

        // Update or spawn displays only for targets near at least one active staff viewer
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.isDead()) {
                continue;
            }
            World world = target.getWorld();
            Location tLoc = target.getLocation();
            boolean anyViewerNear = false;
            for (UUID vid : activeViewers) {
                if (vid.equals(target.getUniqueId())) {
                    continue; // Never float a hologram in front of the moderator's own camera
                }
                Player viewer = Bukkit.getPlayer(vid);
                if (viewer != null && viewer.getWorld() == world
                    && viewer.getLocation().distanceSquared(tLoc) <= cfg.hologramViewDistanceSq) {
                    anyViewerNear = true;
                    break;
                }
            }

            if (!anyViewerNear) {
                removeTargetDisplay(target.getUniqueId());
                continue;
            }

            Location holoLoc = tLoc.clone().add(0.0, cfg.hologramHeightOffset, 0.0);
            PlayerProfile profile = plugin.profile(target);
            Component formattedText = formatHologramText(target, profile, cfg);

            TextDisplay display = targetDisplays.get(target.getUniqueId());
            if (display == null || !display.isValid()) {
                display = world.spawn(holoLoc, TextDisplay.class, d -> {
                    d.setVisibleByDefault(false);
                    d.setPersistent(false);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setShadowed(true);
                    d.setSeeThrough(false);
                    d.setDefaultBackground(false);
                    d.setBackgroundColor(Color.fromARGB(110, 15, 15, 22));
                    d.setTeleportDuration(Math.min(4, cfg.hologramUpdateTicks));
                    d.text(formattedText);
                });
                targetDisplays.put(target.getUniqueId(), display);
            } else {
                display.teleport(holoLoc);
                display.text(formattedText);
            }

            for (UUID vid : activeViewers) {
                Player viewer = Bukkit.getPlayer(vid);
                if (viewer == null) {
                    continue;
                }
                if (!vid.equals(target.getUniqueId())
                    && viewer.getWorld() == world
                    && viewer.getLocation().distanceSquared(tLoc) <= cfg.hologramViewDistanceSq) {
                    viewer.showEntity(plugin, display);
                } else {
                    viewer.hideEntity(plugin, display);
                }
            }
        }
    }

    private Component formatHologramText(Player target, PlayerProfile profile, ACConfig cfg) {
        String avgColor = plugin.alerts().avgColorCode(profile.avgScore, cfg);
        String vlColor = plugin.alerts().classifySeverity(profile.totalVl, cfg).colorCode();
        int ping = BedrockBridgeInfo.getPing(target);
        String line = cfg.hologramFormat
            .replace("{player}", target.getName())
            .replace("{avg_color}", avgColor)
            .replace("{avg}", String.format(Locale.US, "%.1f", profile.avgScore))
            .replace("{vl_color}", vlColor)
            .replace("{vl}", String.format(Locale.US, "%.1f", profile.totalVl))
            .replace("{cps}", String.format(Locale.US, "%.0f", profile.currentCps))
            .replace("{ping}", Integer.toString(ping));
        return ColorUtil.color(line);
    }
}
