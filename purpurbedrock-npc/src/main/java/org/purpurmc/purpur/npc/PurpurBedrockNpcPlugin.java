package org.purpurmc.purpur.npc;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PurpurBedrockNpcPlugin extends JavaPlugin {
    private NpcManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new NpcManager(this);
        NpcCommand commandHandler = new NpcCommand(this, manager);
        PluginCommand command = getCommand("pbnpc");
        if (command == null) {
            throw new IllegalStateException("Command pbnpc is missing from plugin.yml");
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        getServer().getPluginManager().registerEvents(manager, this);
        manager.load();
        getLogger().info("PurpurBedrockNPC 26.2.2 активен: mob-NPC AI=OFF, repeating scheduler tasks=0, одна TextDisplay на голограмму.");
        getLogger().info(NpcManager.runSelfTest());
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }
    }
}
