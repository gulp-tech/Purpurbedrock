package org.purpurmc.purpur.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NpcDefinition {
    final String id;
    EntityType type;
    Location location;
    String hologram;
    double hologramHeight;
    final List<String> actions;

    NpcDefinition(String id, EntityType type, Location location, String hologram, double hologramHeight, List<String> actions) {
        this.id = id;
        this.type = type;
        this.location = location.clone();
        this.hologram = hologram;
        this.hologramHeight = hologramHeight;
        this.actions = new ArrayList<>(actions);
    }

    static NpcDefinition load(String id, ConfigurationSection section, double defaultHeight) {
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) {
            return null;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(section.getString("type", "VILLAGER").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            type = EntityType.VILLAGER;
        }
        Location location = new Location(
            world,
            section.getDouble("x"),
            section.getDouble("y"),
            section.getDouble("z"),
            (float) section.getDouble("yaw"),
            (float) section.getDouble("pitch")
        );
        return new NpcDefinition(
            id,
            type,
            location,
            section.getString("hologram", "<yellow><bold>" + id + "</bold></yellow>"),
            section.getDouble("hologram-height", defaultHeight),
            section.getStringList("actions")
        );
    }

    void save(ConfigurationSection section) {
        section.set("type", type.name());
        section.set("world", location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());
        section.set("hologram", hologram);
        section.set("hologram-height", hologramHeight);
        section.set("actions", new ArrayList<>(actions));
    }
}
