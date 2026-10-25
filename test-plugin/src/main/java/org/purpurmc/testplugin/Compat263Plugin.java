package org.purpurmc.testplugin;

import org.bukkit.plugin.java.JavaPlugin;

public class Compat263Plugin extends JavaPlugin {
    @Override
    public void onEnable() {
        this.getLogger().info("SMOKE_API_26_3_PLUGIN_OK");
    }
}
