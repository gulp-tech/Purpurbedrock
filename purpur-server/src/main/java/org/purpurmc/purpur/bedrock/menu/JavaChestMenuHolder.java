package org.purpurmc.purpur.bedrock.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class JavaChestMenuHolder implements InventoryHolder {
    private final String menuId;
    private final Map<Integer, SlotActions> actionsBySlot = new HashMap<>();
    private Inventory inventory;

    public record SlotActions(
        List<String> anyClickCommands,
        List<String> leftClickCommands,
        List<String> rightClickCommands
    ) {
    }

    public JavaChestMenuHolder(final String menuId) {
        this.menuId = menuId;
    }

    public String getMenuId() {
        return this.menuId;
    }

    public void setInventory(final Inventory inventory) {
        this.inventory = inventory;
    }

    public void putActions(final int slot, final SlotActions actions) {
        this.actionsBySlot.put(slot, actions);
    }

    public @Nullable SlotActions getActions(final int slot) {
        return this.actionsBySlot.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }
}
