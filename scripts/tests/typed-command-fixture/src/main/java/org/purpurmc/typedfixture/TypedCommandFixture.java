package org.purpurmc.typedfixture;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only: alter the advertised tree without changing Essentials' actual Bukkit completer. */
public final class TypedCommandFixture extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @EventHandler public void onCommands(AsyncPlayerSendCommandsEvent<?> event) {
        if (event.isAsynchronous()) return;
        for (String name : List.of("home", "warp", "tpa")) {
            CommandNode root = event.getCommandNode().getChild(name);
            if (root != null) root.addChild(LiteralArgumentBuilder.literal("fixturebranch").executes(context -> 1).build());
        }
    }
}
