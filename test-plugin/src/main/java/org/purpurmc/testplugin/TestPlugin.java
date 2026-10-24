package org.purpurmc.testplugin;

import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class TestPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        org.bukkit.inventory.Inventory inv = this.getServer().createInventory(null, 27, net.kyori.adventure.text.Component.text("JavaPluginChestTest"));
        this.getLogger().info("SMOKE_JAVA_PLUGIN_OK server=" + this.getServer().getName() + " invSize=" + inv.getSize());
    }
}
