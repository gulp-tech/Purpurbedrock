package org.purpurmc.fixture;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin; runs exclusively on the temporary offline CI server. */
public final class CompletionCoverageFixture extends JavaPlugin implements Listener {
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("home")).setTabCompleter((sender, command, label, args) ->
                sender instanceof Player p && args.length == 1 ? List.of(p.getName() + "_home") : List.of());
        Objects.requireNonNull(getCommand("warp")).setTabCompleter((sender, command, label, args) -> List.of());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var root = Commands.literal("wild")
                .then(Commands.argument("amount", IntegerArgumentType.integer())
                    .then(Commands.argument("choice", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            if (context.getSource().getSender() instanceof Player p) builder.suggest(p.getName() + "_typed");
                            return builder.buildFuture();
                        }).executes(ctx -> 1)));
            event.registrar().register(root.build(), "Test typed command");
        });
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        if (event.getPlayer().getName().equals("TabTestA")) event.getPlayer().setOp(true);
        for (String label : List.of("home", "warp", "wild", "gamemode", "damage", "give")) {
            var command = Bukkit.getCommandMap().getCommand(label);
            getLogger().info("COVERAGE_COMMAND_MAP " + label + "=" + (command == null ? "NULL" : command.getClass().getName()));
        }
    }
    @EventHandler public void async(AsyncTabCompleteEvent event) {
        if (event.getSender() instanceof Player p && event.getBuffer().equalsIgnoreCase("/warp "))
            event.setCompletions(List.of(p.getName() + "_async"));
    }
}
