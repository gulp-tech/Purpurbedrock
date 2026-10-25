package org.purpurmc.purpur.anticheat.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.util.Vector;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.gui.ACHolder;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;

public final class ACListener implements Listener {
    private final PurpurBedrockACPlugin plugin;

    public ACListener(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.profile(player);
        profile.playerName = player.getName();
        long now = System.currentTimeMillis();
        profile.exemptUntilMs = now + 4500L;
        profile.resetTimerBucket(now);
        profile.lastSafeLocation = player.getLocation();

        plugin.alerts().onPlayerJoin(player);
        plugin.holograms().onPlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.alerts().onPlayerQuit(player.getUniqueId());
        plugin.holograms().onPlayerQuit(player);
        plugin.trimOfflineProfilesIfNeeded();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        long now = System.currentTimeMillis();
        profile.lastTeleportMs = now;
        profile.exemptUntilMs = Math.max(profile.exemptUntilMs, now + 1600L);
        profile.airTicks = 0;
        profile.hoverTicks = 0;
        profile.wallClimbTicks = 0;
        profile.groundSpoofTicks = 0;
        profile.velocityCheckPending = false;
        profile.resetTimerBucket(now);
        profile.lastSafeLocation = event.getTo().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        long now = System.currentTimeMillis();
        profile.lastTeleportMs = now;
        profile.exemptUntilMs = Math.max(profile.exemptUntilMs, now + 2800L);
        profile.airTicks = 0;
        profile.hoverTicks = 0;
        profile.wallClimbTicks = 0;
        profile.groundSpoofTicks = 0;
        profile.velocityCheckPending = false;
        profile.resetTimerBucket(now);
        profile.lastSafeLocation = event.getRespawnLocation().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        long now = System.currentTimeMillis();
        profile.lastTeleportMs = now;
        profile.exemptUntilMs = Math.max(profile.exemptUntilMs, now + 2800L);
        profile.airTicks = 0;
        profile.hoverTicks = 0;
        profile.wallClimbTicks = 0;
        profile.velocityCheckPending = false;
        profile.resetTimerBucket(now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        Vector vel = event.getVelocity();
        long now = System.currentTimeMillis();
        profile.lastVelocityMs = now;
        double h = Math.hypot(vel.getX(), vel.getZ());
        if (vel.getY() > 0.30 && vel.getY() < 2.5) {
            profile.expectedVelocityY = vel.getY();
            profile.expectedVelocityXZ = h;
            profile.maxObservedPostVelY = 0.0;
            profile.maxObservedPostVelXZ = 0.0;
            profile.velocityCheckPending = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerProfile profile = plugin.profile(player);
            long now = System.currentTimeMillis();
            profile.lastVelocityMs = now;
            String cause = event.getCause().name();
            if (cause.contains("EXPLOSION")) {
                profile.explosionGraceUntilMs = now + 3000L;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        String typeName = proj.getType().name();
        // Wind Charge, Breeze Wind Charge, Fireball, Dragon Fireball, Wither Skull all launch nearby players!
        if (typeName.contains("WIND_CHARGE") || typeName.contains("FIREBALL") || typeName.contains("WITHER_SKULL")) {
            grantExplosionGraceNear(proj.getLocation(), 7.5, 3200L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        grantExplosionGraceNear(event.getLocation(), 9.0, 3200L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        grantExplosionGraceNear(event.getBlock().getLocation(), 9.0, 3200L);
    }

    private void grantExplosionGraceNear(Location center, double radius, long durationMs) {
        World w = center.getWorld();
        if (w == null) {
            return;
        }
        double rSq = radius * radius;
        long until = System.currentTimeMillis() + durationMs;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= rSq) {
                PlayerProfile prof = plugin.profile(p);
                prof.explosionGraceUntilMs = Math.max(prof.explosionGraceUntilMs, until);
                prof.velocityCheckPending = false;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.checks().handleToggleGlide(player, plugin.profile(player), event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        PlayerProfile prof = plugin.profile(event.getPlayer());
        prof.glideOrRiptideGraceUntilMs = System.currentTimeMillis() + 3000L;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            PlayerProfile prof = plugin.profile(player);
            prof.vehicleGraceUntilMs = System.currentTimeMillis() + 2200L;
            prof.airTicks = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHotbarSwitch(PlayerItemHeldEvent event) {
        // Prevent Geyser slot-switch hand-raised desync from ever triggering NoSlow
        plugin.profile(event.getPlayer()).usingItemSprintTicks = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        plugin.profile(event.getPlayer()).usingItemSprintTicks = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        plugin.profile(event.getPlayer()).usingItemSprintTicks = 0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        plugin.checks().handleMove(player, plugin.profile(player), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            plugin.checks().handleCombat(attacker, plugin.profile(attacker), event.getEntity(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        plugin.checks().handleBlockPlace(player, plugin.profile(player), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        plugin.checks().handleBlockDamage(player, plugin.profile(player), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        plugin.checks().handleBlockBreak(player, plugin.profile(player), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerProfile prof = plugin.profile(player);
            Inventory top = event.getView().getTopInventory();
            if (plugin.checks().isRealWorldStorage(top)) {
                prof.worldContainerOpen = true;
                prof.worldContainerOpenedMs = System.currentTimeMillis();
                prof.invMoveTicks = 0;
                prof.resetStealRing();
            } else {
                prof.worldContainerOpen = false;
                prof.invMoveTicks = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerProfile prof = plugin.profile(player);
            prof.worldContainerOpen = false;
            prof.invMoveTicks = 0;
            prof.resetStealRing();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ACHolder holder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player viewer) {
                plugin.menus().handleMenuClick(viewer, holder, event);
            }
            return;
        }
        if (!event.isCancelled() && event.getWhoClicked() instanceof Player player) {
            plugin.checks().handleContainerStealClick(player, plugin.profile(player), top, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ACHolder) {
            event.setCancelled(true);
        }
    }
}
