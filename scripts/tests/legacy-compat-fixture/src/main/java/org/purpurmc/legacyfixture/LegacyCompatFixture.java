package org.purpurmc.legacyfixture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test only: create one real server-side sulfur cube and change its body equipment. */
public final class LegacyCompatFixture extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                Location at = e.getPlayer().getLocation().clone().add(2, 0, 0);
                Mob mob = (Mob) at.getWorld().spawnEntity(at, EntityType.valueOf("SULFUR_CUBE"));
                mob.setAI(false);
                mob.setInvulnerable(true);
                mob.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(Material.STONE));
                getLogger().info("LEGACY_FIXTURE_SET_STONE " + mob.getType());
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    mob.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(Material.AIR));
                    getLogger().info("LEGACY_FIXTURE_SET_AIR");
                }, 40L);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    mob.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(Material.STONE));
                    getLogger().info("LEGACY_FIXTURE_SET_STONE_AGAIN");
                }, 80L);
            } catch (Throwable error) {
                getLogger().severe("LEGACY_FIXTURE_ERROR " + error);
                error.printStackTrace();
            }
        }, 80L);
    }
}
