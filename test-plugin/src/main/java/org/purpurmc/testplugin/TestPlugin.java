package org.purpurmc.testplugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class TestPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Java Menu 26.2"));
        inv.setItem(13, new ItemStack(Material.DIAMOND_SWORD));
        this.getLogger().info(
            "SMOKE_JAVA_PLUGIN_26_2_OK server=" + this.getServer().getName()
                + " mc=" + this.getServer().getMinecraftVersion()
                + " invSize=" + inv.getSize()
        );
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (event.getView().title().equals(Component.text("Java Menu 26.2"))) {
            event.setCancelled(true);
        }
    }
}
