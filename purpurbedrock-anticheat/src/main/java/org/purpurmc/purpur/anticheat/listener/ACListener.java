package org.purpurmc.purpur.anticheat.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
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
        profile.exemptUntilMs = now + 4000L;
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
        profile.exemptUntilMs = Math.max(profile.exemptUntilMs, now + 1500L);
        profile.airTicks = 0;
        profile.hoverTicks = 0;
        profile.velocityCheckPending = false;
        profile.lastSafeLocation = event.getTo().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        long now = System.currentTimeMillis();
        profile.lastTeleportMs = now;
        profile.exemptUntilMs = Math.max(profile.exemptUntilMs, now + 2500L);
        profile.airTicks = 0;
        profile.hoverTicks = 0;
        profile.velocityCheckPending = false;
        profile.lastSafeLocation = event.getRespawnLocation().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        long now = System.currentTimeMillis();
        profile.lastTeleportMs = now;
        profile.exemptUntilMs = Math.max(profile.exemptUntilMs, now + 2500L);
        profile.airTicks = 0;
        profile.hoverTicks = 0;
        profile.velocityCheckPending = false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerProfile profile = plugin.profile(event.getPlayer());
        Vector vel = event.getVelocity();
        long now = System.currentTimeMillis();
        profile.lastVelocityMs = now;
        if (vel.getY() > 0.30 && vel.getY() < 2.5) {
            profile.expectedVelocityY = vel.getY();
            profile.maxObservedPostVelY = 0.0;
            profile.velocityCheckPending = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerProfile profile = plugin.profile(player);
            profile.lastVelocityMs = System.currentTimeMillis();
        }
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ACHolder holder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player viewer) {
                plugin.menus().handleMenuClick(viewer, holder, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ACHolder) {
            event.setCancelled(true);
        }
    }
}
