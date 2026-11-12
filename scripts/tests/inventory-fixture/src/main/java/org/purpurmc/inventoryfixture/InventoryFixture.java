package org.purpurmc.inventoryfixture;

import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.entity.Item;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable packet-test plugin; never packaged in the server. */
public final class InventoryFixture extends JavaPlugin implements Listener {
    private static final Material[] ITEMS = {Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT};
    private static final int[] SIZES = {27, 54, 9};
    private Inventory integrityChest;
    private NamespacedKey marker;
    private int cancelledMergeAttempts;

    private ItemStack marked(int amount) {
        ItemStack stack = new ItemStack(Material.DIAMOND, amount);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, "integrity-test");
        stack.setItemMeta(meta);
        return stack;
    }
    private ItemStack dropStack(Material type, int amount, String value) {
        ItemStack stack = new ItemStack(type, amount);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
        return stack;
    }
    private String marker(ItemStack stack) {
        return stack == null || !stack.hasItemMeta() ? null
                : stack.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }
    private void drop(World world, int x, int y, int z, Material type, int amount, String value) {
        world.getChunkAt(x >> 4, z >> 4);
        Item item = world.dropItem(new Location(world, x + 0.25, y + 0.1, z + 0.25), dropStack(type, amount, value));
        item.setVelocity(new Vector(0, 0, 0));
        item.setGravity(false);
        item.setPickupDelay(32767);
    }
    private String dropResult(World world, String key, int expectedItems, int expectedSum) {
        var items = world.getEntitiesByClass(Item.class).stream().filter(i -> key.equals(marker(i.getItemStack()))).toList();
        int total = items.stream().mapToInt(i -> i.getItemStack().getAmount()).sum();
        boolean okay = items.size() == expectedItems && total == expectedSum
                && items.stream().allMatch(i -> i.getItemStack().getAmount() <= i.getItemStack().getMaxStackSize());
        return key + "=" + (okay ? "PASS" : "FAIL") + "(" + items.size() + "," + total + ")";
    }
    private void runDropTest(Player player) {
        World world = player.getWorld();
        int x = player.getLocation().getBlockX(), z = player.getLocation().getBlockZ();
        int y = Math.min(world.getMaxHeight() - 4, Math.max(80, player.getLocation().getBlockY() + 8));
        cancelledMergeAttempts = 0;
        drop(world, x, y, z, Material.STONE, 40, "merge");
        drop(world, x + 2, y, z, Material.STONE, 10, "merge");
        drop(world, x + 12, y, z, Material.STONE, 40, "partial");
        drop(world, x + 14, y, z, Material.STONE, 40, "partial");
        drop(world, x + 24, y, z, Material.STONE, 20, "different-a");
        drop(world, x + 26, y, z, Material.STONE, 30, "different-b");
        drop(world, x + 36, y, z, Material.STONE, 20, "deny");
        drop(world, x + 38, y, z, Material.STONE, 30, "deny");
        world.getBlockAt(x + 49, y, z).setType(Material.STONE);
        world.getBlockAt(x + 49, y + 1, z).setType(Material.STONE);
        drop(world, x + 48, y, z, Material.STONE, 20, "wall");
        drop(world, x + 50, y, z, Material.STONE, 30, "wall");
        drop(world, x + 60, y, z, Material.DIAMOND, 20, "nonblock");
        drop(world, x + 62, y, z, Material.DIAMOND, 30, "nonblock");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String result = String.join(" ", dropResult(world, "merge", 1, 50), dropResult(world, "partial", 2, 80),
                    dropResult(world, "different-a", 1, 20), dropResult(world, "different-b", 1, 30),
                    dropResult(world, "deny", 2, 50), dropResult(world, "wall", 2, 50),
                    dropResult(world, "nonblock", 2, 50), "cancelEvents=" + cancelledMergeAttempts);
            player.sendMessage("DROPTEST " + result);
        }, 200L);
    }
    @EventHandler public void cancelFixtureMerge(ItemMergeEvent event) {
        if ("deny".equals(marker(event.getEntity().getItemStack())) && "deny".equals(marker(event.getTarget().getItemStack()))) {
            cancelledMergeAttempts++;
            event.setCancelled(true);
        }
    }
    private int markedAmount(ItemStack stack) {
        if (stack == null || stack.getType() != Material.DIAMOND || !stack.hasItemMeta()) return 0;
        String value = stack.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        return "integrity-test".equals(value) ? stack.getAmount() : 0;
    }
    private int count(ItemStack[] items) {
        int sum = 0;
        for (ItemStack item : items) sum += markedAmount(item);
        return sum;
    }
    private void reportMenuLeak(Player player) {
        Set<Material> icons = Set.of(Material.DIAMOND_ORE, Material.ARROW, Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT);
        int owned = 0;
        for (ItemStack item : player.getInventory().getContents()) if (item != null && icons.contains(item.getType())) owned += item.getAmount();
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && icons.contains(cursor.getType())) owned += cursor.getAmount();
        int ground = player.getWorld().getEntitiesByClass(Item.class).stream()
                .map(Item::getItemStack).filter(i -> icons.contains(i.getType())).mapToInt(ItemStack::getAmount).sum();
        player.sendMessage("MENU_INTEGRITY owned=" + owned + " ground=" + ground);
    }
    private void report(Player player) {
        int chest = integrityChest == null ? 0 : count(integrityChest.getContents());
        int inventory = count(player.getInventory().getContents());
        int cursor = markedAmount(player.getItemOnCursor());
        int ground = player.getWorld().getEntitiesByClass(Item.class).stream().mapToInt(e -> markedAmount(e.getItemStack())).sum();
        player.sendMessage("INTEGRITY chest=" + chest + " inventory=" + inventory + " cursor=" + cursor + " ground=" + ground + " total=" + (chest + inventory + cursor + ground));
    }
    private static final class Holder implements InventoryHolder {
        final int page;
        final Inventory inventory;
        Holder(int page) {
            this.page = page;
            inventory = Bukkit.createInventory(this, SIZES[page], Component.text("Fixture page " + page));
            inventory.setItem(0, new ItemStack(ITEMS[page]));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    @Override public void onEnable() {
        marker = new NamespacedKey(this, "test_id");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("integrityfixture")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player) || args.length != 1) return true;
            if (args[0].equals("start")) {
                player.closeInventory();
                player.getInventory().clear();
                player.setItemOnCursor(null);
                player.getInventory().setItem(9, marked(10));
                integrityChest = Bukkit.createInventory(null, 27, Component.text("Integrity fixture"));
                integrityChest.setItem(0, marked(20));
                player.openInventory(integrityChest);
            } else if (args[0].equals("check")) report(player);
            else if (args[0].equals("drops")) runDropTest(player);
            else if (args[0].equals("menucheck")) reportMenuLeak(player);
            return true;
        });
        Objects.requireNonNull(getCommand("inventoryfixture")).setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) player.openInventory(new Holder(0).inventory);
            return true;
        });
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 0 || !(event.getWhoClicked() instanceof Player player)) return;
        if (holder.page < 2) Bukkit.getScheduler().runTask(this, () -> player.openInventory(new Holder(holder.page + 1).inventory));
    }
}
