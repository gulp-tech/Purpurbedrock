package org.purpurmc.inventoryfixture;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

    private ItemStack marked(int amount) {
        ItemStack stack = new ItemStack(Material.DIAMOND, amount);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, "integrity-test");
        stack.setItemMeta(meta);
        return stack;
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
