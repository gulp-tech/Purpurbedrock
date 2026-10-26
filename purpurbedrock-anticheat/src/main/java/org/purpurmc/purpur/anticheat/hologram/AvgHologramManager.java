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
import org.bukkit.util.Vector;
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
 * Device-, network-, and TPS-adaptive zero-hitbox AVG floating hologram above players.
 *
 * - Visible ONLY to staff who hold {@code purpurac.hologram} and toggled it ON.
 * - Mobile, console and desktop viewers receive separate distance/entity budgets; the shared update
 *   cadence follows the weakest active viewer so a phone never gets a 20 Hz TextDisplay packet flood.
 * - When no staff has holograms enabled, {@code hasViewers == false}: ZERO entities and ZERO tasks run!
 */
public final class AvgHologramManager {
    private final PurpurBedrockACPlugin plugin;
    private final Set<UUID> activeViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TextDisplay> targetDisplays = new HashMap<>();
    private volatile boolean hasViewers = false;
    private BukkitTask updateTask;
    private long tickCounter = 0L;
    private long renderPassCounter = 0L;

    private record RenderingBudget(int updateTicks, double viewDistanceSq, int maxTargets) {
    }

    public AvgHologramManager(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasActiveViewers() {
        return hasViewers;
    }

    public void onPlayerJoin(Player player) {
        ACConfig cfg = plugin.cfg();
        if (cfg.hologramEnabled && cfg.hologramAutoEnableOnJoin && player.hasPermission(cfg.permHologram)) {
            activeViewers.add(player.getUniqueId());
            hasViewers = true;
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

    /**
     * Immediate same-tick hologram position sync on PlayerMoveEvent / PlayerTeleportEvent.
     * Costs 0 ns when no moderator has /pac holo enabled.
     */
    public void onTargetMove(Player target, Location from, Location to) {
        if (!hasViewers || to == null) {
            return;
        }
        TextDisplay display = targetDisplays.get(target.getUniqueId());
        if (display == null || !display.isValid() || display.getWorld() != to.getWorld()) {
            return;
        }
        ACConfig cfg = plugin.cfg();
        // Adaptive mode intentionally coalesces movement into scheduled render passes. Calling
        // TextDisplay#teleport for every PlayerMoveEvent was the main hologram packet/CPU burst.
        if (cfg.hologramAdaptiveRendering) {
            return;
        }
        double lead = cfg.hologramVelocityLead;
        double leadX = 0.0;
        double leadY = 0.0;
        double leadZ = 0.0;
        if (from != null && from.getWorld() == to.getWorld() && lead > 0.0) {
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double dz = to.getZ() - from.getZ();
            if (dx * dx + dz * dz < 4.0) {
                leadX = Math.clamp(dx * lead, -0.45, 0.45);
                leadY = Math.clamp(dy * (lead * 0.55), -0.25, 0.30);
                leadZ = Math.clamp(dz * lead, -0.45, 0.45);
            }
        }
        Location holoLoc = new Location(
            to.getWorld(),
            to.getX() + leadX,
            to.getY() + cfg.hologramHeightOffset + leadY,
            to.getZ() + leadZ
        );
        display.teleport(holoLoc);
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
        hasViewers = true;
        ensureTaskState();
        tickCounter = 0L;
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
            hasViewers = true;
            ensureTaskState();
        }
    }

    public void shutdownAllDisplays() {
        hasViewers = false;
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
        hasViewers = true;
        if (updateTask == null) {
            // Adaptive cadence is decided per pass from live device/RTT/TPS data.
            int period = cfg.hologramAdaptiveRendering ? 1 : Math.max(1, cfg.hologramUpdateTicks);
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHolograms, 1L, period);
        }
    }

    private void tickHolograms() {
        ACConfig cfg = plugin.cfg();
        if (!cfg.hologramEnabled || activeViewers.isEmpty()) {
            shutdownAllDisplays();
            return;
        }

        RenderingBudget sharedBudget = sharedRenderingBudget(cfg);
        if ((tickCounter++ % Math.max(1, sharedBudget.updateTicks())) != 0L) {
            return;
        }
        boolean refreshTextAndViewers = (renderPassCounter++ % 4L) == 0L;

        if (refreshTextAndViewers) {
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
        }

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

        double lead = cfg.hologramVelocityLead;
        int renderedTargets = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.isDead()) {
                continue;
            }
            if (renderedTargets >= sharedBudget.maxTargets()) {
                removeTargetDisplay(target.getUniqueId());
                continue;
            }
            World world = target.getWorld();
            Location tLoc = target.getLocation();

            TextDisplay display = targetDisplays.get(target.getUniqueId());
            if (!cfg.hologramAdaptiveRendering && !refreshTextAndViewers && display != null && display.isValid()) {
                Vector vel = target.getVelocity();
                Location holoLoc = new Location(
                    world,
                    tLoc.getX() + Math.clamp(vel.getX() * lead, -0.45, 0.45),
                    tLoc.getY() + cfg.hologramHeightOffset,
                    tLoc.getZ() + Math.clamp(vel.getZ() * lead, -0.45, 0.45)
                );
                display.teleport(holoLoc);
                continue;
            }

            boolean anyViewerNear = false;
            for (UUID vid : activeViewers) {
                if (vid.equals(target.getUniqueId())) {
                    continue;
                }
                Player viewer = Bukkit.getPlayer(vid);
                RenderingBudget viewerBudget = viewer != null ? renderingBudget(viewer, cfg) : null;
                if (viewer != null && viewer.getWorld() == world
                    && viewer.getLocation().distanceSquared(tLoc) <= viewerBudget.viewDistanceSq()) {
                    anyViewerNear = true;
                    break;
                }
            }

            if (!anyViewerNear) {
                removeTargetDisplay(target.getUniqueId());
                continue;
            }
            renderedTargets++;

            Vector vel = target.getVelocity();
            Location holoLoc = new Location(
                world,
                tLoc.getX() + Math.clamp(vel.getX() * lead, -0.45, 0.45),
                tLoc.getY() + cfg.hologramHeightOffset,
                tLoc.getZ() + Math.clamp(vel.getZ() * lead, -0.45, 0.45)
            );
            PlayerProfile profile = plugin.profile(target);
            Component formattedText = formatHologramText(target, profile, cfg);

            if (display == null || !display.isValid()) {
                display = world.spawn(holoLoc, TextDisplay.class, d -> {
                    d.setVisibleByDefault(false);
                    d.setPersistent(false);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setShadowed(true);
                    d.setSeeThrough(false);
                    d.setDefaultBackground(false);
                    d.setBackgroundColor(Color.fromARGB(115, 15, 15, 22));
                    d.setTeleportDuration(Math.clamp(sharedBudget.updateTicks(), 0, 59));
                    d.setInterpolationDuration(Math.clamp(sharedBudget.updateTicks(), 0, 59));
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
                RenderingBudget viewerBudget = renderingBudget(viewer, cfg);
                if (!vid.equals(target.getUniqueId())
                    && viewer.getWorld() == world
                    && viewer.getLocation().distanceSquared(tLoc) <= viewerBudget.viewDistanceSq()) {
                    viewer.showEntity(plugin, display);
                } else {
                    viewer.hideEntity(plugin, display);
                }
            }
        }
    }

    private RenderingBudget sharedRenderingBudget(ACConfig cfg) {
        if (!cfg.hologramAdaptiveRendering) {
            return new RenderingBudget(1, cfg.hologramViewDistanceSq, Integer.MAX_VALUE);
        }
        int updateTicks = 1;
        double distanceSq = cfg.hologramViewDistanceSq;
        int maxTargets = Integer.MAX_VALUE;
        boolean found = false;
        for (UUID viewerId : activeViewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            RenderingBudget budget = renderingBudget(viewer, cfg);
            // Shared TextDisplay transforms must respect the weakest active viewer.
            updateTicks = Math.max(updateTicks, budget.updateTicks());
            distanceSq = found ? Math.min(distanceSq, budget.viewDistanceSq()) : budget.viewDistanceSq();
            maxTargets = Math.min(maxTargets, budget.maxTargets());
            found = true;
        }
        if (!found) {
            return new RenderingBudget(10, 64.0, 1);
        }
        return new RenderingBudget(updateTicks, distanceSq, Math.max(1, maxTargets));
    }

    private RenderingBudget renderingBudget(Player viewer, ACConfig cfg) {
        if (!cfg.hologramAdaptiveRendering) {
            return new RenderingBudget(1, cfg.hologramViewDistanceSq, Integer.MAX_VALUE);
        }
        String device = BedrockBridgeInfo.getDeviceId(viewer);
        int ticks;
        double distanceSq;
        int maxTargets;
        switch (device) {
            case "android", "ios", "phone_kbm" -> {
                ticks = cfg.hologramMobileUpdateTicks;
                distanceSq = cfg.hologramMobileViewDistanceSq;
                maxTargets = cfg.hologramMobileMaxTargets;
            }
            case "xbox", "ps4_5" -> {
                ticks = cfg.hologramConsoleUpdateTicks;
                distanceSq = cfg.hologramConsoleViewDistanceSq;
                maxTargets = cfg.hologramConsoleMaxTargets;
            }
            default -> {
                ticks = cfg.hologramDesktopUpdateTicks;
                distanceSq = cfg.hologramDesktopViewDistanceSq;
                maxTargets = cfg.hologramDesktopMaxTargets;
            }
        }

        int ping = BedrockBridgeInfo.getPing(viewer);
        if (ping >= cfg.hologramDegradedPingMs) {
            ticks = Math.min(20, ticks + 2);
            distanceSq *= cfg.hologramDegradedDistanceMultiplier * cfg.hologramDegradedDistanceMultiplier;
            maxTargets = Math.max(2, (int) Math.floor(maxTargets * 0.65));
        }
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        if (tps < cfg.hologramPressureTps) {
            ticks = Math.max(ticks, 8);
            distanceSq *= 0.56; // 0.75 distance, squared
            maxTargets = Math.max(2, maxTargets / 2);
        }
        return new RenderingBudget(ticks, distanceSq, maxTargets);
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
