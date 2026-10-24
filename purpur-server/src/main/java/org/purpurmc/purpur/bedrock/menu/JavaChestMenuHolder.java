package org.purpurmc.purpur.bedrock.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NullMarked;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * Built-in InventoryHolder for Purpurbedrock's native Java Chest Menus,
 * rendered on Bedrock Edition as interactive chest containers alongside all
 * Bukkit/Paper menu plugins (DeluxeMenus, VoidCases, VoidClans, LMstash, etc.).
 */
@NullMarked
public final class JavaChestMenuHolder implements InventoryHolder {
    private final String menuId;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> slotActions = new HashMap<>();

    public JavaChestMenuHolder(String menuId, int size, Component title) {
        this.menuId = menuId;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public String getMenuId() {
        return this.menuId;
    }

    public void setAction(int slot, Consumer<Player> action) {
        this.slotActions.put(slot, action);
    }

    public Consumer<Player> getAction(int slot) {
        return this.slotActions.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }
}
