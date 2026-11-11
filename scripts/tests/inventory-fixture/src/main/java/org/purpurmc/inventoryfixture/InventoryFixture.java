package org.purpurmc.inventoryfixture;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
        getServer().getPluginManager().registerEvents(this, this);
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
