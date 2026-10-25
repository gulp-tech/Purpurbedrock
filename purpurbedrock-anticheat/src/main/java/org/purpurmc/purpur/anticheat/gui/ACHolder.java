package org.purpurmc.purpur.anticheat.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class ACHolder implements InventoryHolder {
    public enum MenuType {
        OVERVIEW,
        PLAYER_HISTORY,
        CHECKS_CONFIG
    }

    private final MenuType menuType;
    private final UUID targetUuid;
    private final int page;
    private final Inventory inventory;
    private final UUID[] slotPlayerTargets = new UUID[54];
    private final String[] slotCheckKeys = new String[54];

    public ACHolder(MenuType menuType, UUID targetUuid, int page, Component title) {
        this.menuType = menuType;
        this.targetUuid = targetUuid;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54, title);
    }

    public MenuType menuType() {
        return menuType;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public int page() {
        return page;
    }

    public void setSlotTarget(int slot, UUID uuid) {
        if (slot >= 0 && slot < slotPlayerTargets.length) {
            slotPlayerTargets[slot] = uuid;
        }
    }

    public UUID getSlotTarget(int slot) {
        return (slot >= 0 && slot < slotPlayerTargets.length) ? slotPlayerTargets[slot] : null;
    }

    public void setSlotCheckKey(int slot, String key) {
        if (slot >= 0 && slot < slotCheckKeys.length) {
            slotCheckKeys[slot] = key;
        }
    }

    public String getSlotCheckKey(int slot) {
        return (slot >= 0 && slot < slotCheckKeys.length) ? slotCheckKeys[slot] : null;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
