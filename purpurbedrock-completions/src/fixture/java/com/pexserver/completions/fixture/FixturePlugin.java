package com.pexserver.completions.fixture;

import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.event.server.TabCompleteEvent;

/** Test-only fake homes provider: not included in the product. */
public final class FixturePlugin extends JavaPlugin implements Listener {
    private final Map<UUID, List<String>> homes = new HashMap<>();
    private List<String> homes(Player player) {
        return homes.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>(List.of(player.getName() + "_base", player.getName() + "_farm",
                player.getName() + "_mine", player.getName() + "_village", player.getName() + "_other")));
    }
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("gearfixture")).setExecutor((sender, command, alias, args) -> {
            if (!(sender instanceof Player player) || args.length != 1) return true;
            boolean on = args[0].equals("on");
            var inventory = player.getInventory();
            inventory.setHelmet(on ? new ItemStack(Material.DIAMOND_HELMET) : null);
            inventory.setChestplate(on ? new ItemStack(Material.IRON_CHESTPLATE) : null);
            inventory.setLeggings(on ? new ItemStack(Material.GOLDEN_LEGGINGS) : null);
            inventory.setBoots(on ? new ItemStack(Material.CHAINMAIL_BOOTS) : null);
            inventory.setItemInMainHand(on ? new ItemStack(Material.IRON_SWORD) : null);
            inventory.setItemInOffHand(on ? new ItemStack(Material.SHIELD) : null);
            player.sendMessage("fixture:equipment:" + (on ? "on" : "off"));
            return true;
        });
        Objects.requireNonNull(getCommand("modelfixture")).setExecutor((sender, command, alias, args) -> {
            if (!(sender instanceof Player player) || args.length != 1) return true;
            ItemStack paper = new ItemStack(Material.PAPER);
            var meta = paper.getItemMeta();
            if (args[0].equals("custom")) {
                meta.setItemModel(NamespacedKey.fromString("voidspheres:sphere1"));
            } else if (args[0].equals("other")) {
                meta.setItemModel(NamespacedKey.fromString("example:other"));
            } else if (args[0].equals("marked") || args[0].equals("wrongmarker")) {
                meta.getPersistentDataContainer().set(
                    Objects.requireNonNull(NamespacedKey.fromString("fixture:custom_id")),
                    PersistentDataType.STRING, args[0].equals("marked") ? "sphere1" : "other");
            } else if (!args[0].equals("vanilla")) {
                return true;
            }
            paper.setItemMeta(meta);
            player.getInventory().setItemInMainHand(paper);
            return true;
        });
        Objects.requireNonNull(getCommand("privatetest")).setTabCompleter((s, c, a, v) -> List.of("secret_value"));
        Objects.requireNonNull(getCommand("home")).setTabCompleter((sender, command, alias, args) ->
                sender instanceof Player player && args.length == 1
                        ? homes(player).stream().filter(s -> s.startsWith(args[0])).toList() : List.of());
        Objects.requireNonNull(getCommand("delhome")).setTabCompleter((sender, command, alias, args) -> {
            if (!(sender instanceof Player player)) return List.of();
            if (args.length == 1) return List.of("delete", "set").stream().filter(s -> s.startsWith(args[0])).toList();
            if (args.length == 2 && args[0].equals("delete")) return homes(player).stream().filter(s -> s.startsWith(args[1])).toList();
            return List.of();
        });
        Objects.requireNonNull(getCommand("delhome")).setExecutor((sender, command, alias, args) -> {
            if (!(sender instanceof Player player) || args.length < 2) return true;
            if (args[0].equals("set")) homes(player).add(args[1]);
            if (args[0].equals("delete")) homes(player).remove(args[1]);
            player.sendMessage("fixture:" + String.join(",", homes(player)));
            return true;
        });
    }
    @EventHandler public void onAsync(AsyncTabCompleteEvent event) {
        if (event.getSender() instanceof Player p && event.getBuffer().equalsIgnoreCase("/warp "))
            event.setCompletions(List.of(p.getName() + "_async"));
        if (event.getBuffer().equalsIgnoreCase("/pay ")) event.setCancelled(true);
    }
    @EventHandler public void onSync(TabCompleteEvent event) {
        if (event.getSender() instanceof Player p && event.getBuffer().equalsIgnoreCase("/tpa "))
            event.setCompletions(List.of(p.getName() + "_sync"));
        if (event.getBuffer().equalsIgnoreCase("/pay ")) event.setCompletions(List.of("blocked_secret"));
    }
}
