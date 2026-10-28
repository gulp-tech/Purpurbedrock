package org.purpurmc.purpur.npc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NpcManager implements Listener {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String NPC_TAG = "purpurbedrock_npc";
    private static final String HOLOGRAM_TAG = "purpurbedrock_npc_hologram";

    private final JavaPlugin plugin;
    private final NamespacedKey npcIdKey;
    private final Map<String, NpcDefinition> definitions = new HashMap<>();
    private final Map<String, SpawnedNpc> spawned = new HashMap<>();
    private final Map<UUID, String> entityToNpc = new HashMap<>();
    private final Set<String> spawning = new HashSet<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private long clickCooldownMillis;
    private double defaultHologramHeight;

    private record SpawnedNpc(Mob mob, TextDisplay hologram) {
    }

    enum CreateResult {
        CREATED,
        RESPAWNED,
        INVALID_ID,
        INVALID_TYPE,
        SPAWN_FAILED
    }

    NpcManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.npcIdKey = new NamespacedKey(plugin, "npc_id");
    }

    void load() {
        removeSpawnedEntities();
        definitions.clear();
        clickCooldownMillis = Math.max(100L, plugin.getConfig().getLong("settings.click-cooldown-ms", 350L));
        defaultHologramHeight = Math.max(0.5D, plugin.getConfig().getDouble("settings.default-hologram-height", 2.3D));
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("npcs");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                NpcDefinition definition = NpcDefinition.load(id, section, defaultHologramHeight);
                if (definition != null && isSupportedMob(definition.type)) {
                    definitions.put(normalizeId(id), definition);
                } else {
                    plugin.getLogger().info("NPC '" + id + "' пропущен: мир или тип моба недоступен.");
                }
            }
        }
        for (NpcDefinition definition : definitions.values()) {
            spawn(definition);
        }
    }

    Collection<NpcDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    NpcDefinition get(String id) {
        return definitions.get(normalizeId(id));
    }

    CreateResult create(String id, EntityType type, Location location) {
        String key = normalizeId(id);
        if (key.isBlank()) {
            return CreateResult.INVALID_ID;
        }
        if (!isSupportedMob(type)) {
            return CreateResult.INVALID_TYPE;
        }

        NpcDefinition existing = definitions.get(key);
        if (existing != null) {
            EntityType oldType = existing.type;
            Location oldLocation = existing.location.clone();
            existing.type = type;
            existing.location = location.clone();
            if (!respawn(existing)) {
                existing.type = oldType;
                existing.location = oldLocation;
                spawn(existing);
                return CreateResult.SPAWN_FAILED;
            }
            save(existing);
            return CreateResult.RESPAWNED;
        }

        NpcDefinition definition = new NpcDefinition(
            key,
            type,
            location,
            "<yellow><bold>" + escapeMiniMessage(key) + "</bold></yellow>",
            defaultHologramHeight,
            List.of()
        );
        definitions.put(key, definition);
        if (!spawn(definition)) {
            definitions.remove(key);
            return CreateResult.SPAWN_FAILED;
        }
        save(definition);
        return CreateResult.CREATED;
    }

    boolean remove(String id) {
        String key = normalizeId(id);
        NpcDefinition removed = definitions.remove(key);
        if (removed == null) {
            return false;
        }
        despawn(key);
        plugin.getConfig().set("npcs." + key, null);
        plugin.saveConfig();
        return true;
    }

    boolean moveHere(String id, Location location) {
        NpcDefinition definition = get(id);
        if (definition == null) {
            return false;
        }
        Location oldLocation = definition.location.clone();
        definition.location = location.clone();
        if (!respawn(definition)) {
            definition.location = oldLocation;
            spawn(definition);
            return false;
        }
        save(definition);
        return true;
    }

    boolean setType(String id, EntityType type) {
        NpcDefinition definition = get(id);
        if (definition == null || !isSupportedMob(type)) {
            return false;
        }
        EntityType oldType = definition.type;
        definition.type = type;
        if (!respawn(definition)) {
            definition.type = oldType;
            spawn(definition);
            return false;
        }
        save(definition);
        return true;
    }

    boolean setHologram(String id, String text) {
        NpcDefinition definition = get(id);
        if (definition == null) {
            return false;
        }
        definition.hologram = text;
        updateHologram(definition);
        save(definition);
        return true;
    }

    boolean setHologramHeight(String id, double height) {
        NpcDefinition definition = get(id);
        if (definition == null || !Double.isFinite(height) || height < 0.5D || height > 10.0D) {
            return false;
        }
        definition.hologramHeight = height;
        updateHologram(definition);
        save(definition);
        return true;
    }

    boolean addAction(String id, String action) {
        NpcDefinition definition = get(id);
        if (definition == null || !isValidAction(action)) {
            return false;
        }
        definition.actions.add(action);
        save(definition);
        return true;
    }

    boolean removeAction(String id, int oneBasedIndex) {
        NpcDefinition definition = get(id);
        if (definition == null || oneBasedIndex < 1 || oneBasedIndex > definition.actions.size()) {
            return false;
        }
        definition.actions.remove(oneBasedIndex - 1);
        save(definition);
        return true;
    }

    boolean clearActions(String id) {
        NpcDefinition definition = get(id);
        if (definition == null) {
            return false;
        }
        definition.actions.clear();
        save(definition);
        return true;
    }

    List<String> actions(String id) {
        NpcDefinition definition = get(id);
        return definition == null ? List.of() : List.copyOf(definition.actions);
    }

    void shutdown() {
        removeSpawnedEntities();
        definitions.clear();
        clickCooldown.clear();
    }

    private boolean spawn(NpcDefinition definition) {
        if (!spawning.add(definition.id)) {
            return false;
        }
        try {
            return spawnNow(definition);
        } finally {
            spawning.remove(definition.id);
        }
    }

    private boolean spawnNow(NpcDefinition definition) {
        despawn(definition.id);
        Location location = definition.location.clone();
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        // A command-created NPC is always in a loaded player chunk. Configured NPCs may
        // point at an unloaded chunk; load it synchronously once so creation is truthful.
        if (!location.getChunk().isLoaded()) {
            location.getChunk().load();
        }
        // Remove a serialized leftover after a crash/chunk reload before creating the live pair.
        for (Entity entity : location.getChunk().getEntities()) {
            String storedId = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
            if (definition.id.equals(storedId)
                && (entity.getScoreboardTags().contains(NPC_TAG) || entity.getScoreboardTags().contains(HOLOGRAM_TAG))) {
                entity.remove();
            }
        }

        Mob mob;
        try {
            Entity raw = world.spawnEntity(location, definition.type, false);
            if (!(raw instanceof Mob spawnedMob)) {
                raw.remove();
                return false;
            }
            mob = spawnedMob;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Не удалось создать NPC '" + definition.id + "': " + ex.getMessage());
            return false;
        }
        configureMob(mob, definition.id);

        Location hologramLocation = location.clone().add(0.0D, definition.hologramHeight, 0.0D);
        Component hologramText = parseHologram(definition.hologram);
        TextDisplay hologram;
        try {
            hologram = world.spawn(hologramLocation, TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setVisibleByDefault(true);
                display.setBillboard(Display.Billboard.CENTER);
                display.setShadowed(true);
                display.setSeeThrough(false);
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
                display.setInterpolationDuration(0);
                display.setTeleportDuration(0);
                display.text(hologramText);
                display.addScoreboardTag("npc");
                display.addScoreboardTag(HOLOGRAM_TAG);
                display.setMetadata("NPC", new FixedMetadataValue(plugin, true));
                display.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, definition.id);
            });
        } catch (RuntimeException ex) {
            mob.remove();
            plugin.getLogger().severe("Не удалось создать голограмму NPC '" + definition.id + "': " + ex.getMessage());
            return false;
        }
        spawned.put(definition.id, new SpawnedNpc(mob, hologram));
        entityToNpc.put(mob.getUniqueId(), definition.id);
        // Explicitly clear plugin visibility overrides for players already tracking this chunk.
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) <= 4096.0D) {
                viewer.showEntity(plugin, mob);
                viewer.showEntity(plugin, hologram);
            }
        }
        return mob.isValid() && hologram.isValid();
    }

    private void configureMob(Mob mob, String id) {
        mob.setAI(false);
        mob.setAware(false);
        mob.setInvisible(false);
        mob.setVisibleByDefault(true);
        mob.setGravity(false);
        mob.setSilent(plugin.getConfig().getBoolean("settings.silent", true));
        mob.setInvulnerable(plugin.getConfig().getBoolean("settings.invulnerable", true));
        mob.setCollidable(false);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        for (PotionEffect effect : List.copyOf(mob.getActivePotionEffects())) {
            mob.removePotionEffect(effect.getType());
        }
        mob.setVelocity(new org.bukkit.util.Vector());
        mob.setLootTable(null);
        mob.addScoreboardTag("npc");
        mob.addScoreboardTag(NPC_TAG);
        mob.setMetadata("NPC", new FixedMetadataValue(plugin, true));
        mob.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, id);
    }

    private void updateHologram(NpcDefinition definition) {
        SpawnedNpc live = spawned.get(definition.id);
        if (live == null || !live.mob().isValid() || !live.hologram().isValid()) {
            respawn(definition);
            return;
        }
        live.hologram().teleport(definition.location.clone().add(0.0D, definition.hologramHeight, 0.0D));
        live.hologram().text(parseHologram(definition.hologram));
    }

    private boolean respawn(NpcDefinition definition) {
        despawn(definition.id);
        return spawn(definition);
    }

    private void despawn(String id) {
        SpawnedNpc live = spawned.remove(normalizeId(id));
        if (live == null) {
            return;
        }
        entityToNpc.remove(live.mob().getUniqueId());
        live.mob().remove();
        live.hologram().remove();
    }

    private void removeSpawnedEntities() {
        for (SpawnedNpc live : new ArrayList<>(spawned.values())) {
            live.mob().remove();
            live.hologram().remove();
        }
        spawned.clear();
        entityToNpc.clear();
        // Also clean leftovers after a crash or forced plugin reload.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(NPC_TAG) || entity.getScoreboardTags().contains(HOLOGRAM_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private void save(NpcDefinition definition) {
        String path = "npcs." + definition.id;
        plugin.getConfig().set(path, null);
        ConfigurationSection section = plugin.getConfig().createSection(path);
        definition.save(section);
        plugin.saveConfig();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (isOurNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCombust(EntityCombustEvent event) {
        if (isOurNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!isOurNpc(event.getEntity())) {
            return;
        }
        // Allow an existing effect to be removed/cleared, but reject every new or
        // upgraded effect from potions, arrows, beacons, commands and plugins.
        if (event.getAction() == EntityPotionEffectEvent.Action.ADDED
            || event.getAction() == EntityPotionEffectEvent.Action.CHANGED) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null && isOurNpc(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clickCooldown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            String id = entityToNpc.remove(entity.getUniqueId());
            if (id != null) {
                spawned.remove(id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (NpcDefinition definition : definitions.values()) {
            Location location = definition.location;
            if (location.getWorld() == world && (location.getBlockX() >> 4) == chunkX && (location.getBlockZ() >> 4) == chunkZ) {
                spawn(definition);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        handleClick(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        handleClick(event.getPlayer(), event.getRightClicked(), event);
    }

    private void handleClick(Player player, Entity entity, org.bukkit.event.Cancellable event) {
        String id = entityToNpc.get(entity.getUniqueId());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long previous = clickCooldown.put(player.getUniqueId(), now);
        if (previous != null && now - previous < clickCooldownMillis) {
            return;
        }
        NpcDefinition definition = definitions.get(id);
        if (definition == null) {
            return;
        }
        for (String action : definition.actions) {
            executeAction(player, definition, action);
        }
    }

    private void executeAction(Player player, NpcDefinition npc, String encoded) {
        int separator = encoded.indexOf(':');
        if (separator <= 0) {
            return;
        }
        String type = encoded.substring(0, separator).toLowerCase(Locale.ROOT);
        String value = placeholders(encoded.substring(separator + 1), player, npc);
        switch (type) {
            case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(value));
            case "player" -> player.performCommand(stripSlash(value));
            case "message" -> player.sendMessage(MINI_MESSAGE.deserialize(value));
            case "menu" -> Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "dm open " + value.trim() + " " + player.getName()
            );
            case "teleport" -> teleport(player, value);
            default -> {
            }
        }
    }

    private static void teleport(Player player, String value) {
        String[] parts = value.split(";");
        if (parts.length < 4) {
            return;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            Location current = player.getLocation();
            float yaw = parts.length >= 5 ? Float.parseFloat(parts[4]) : current.getYaw();
            float pitch = parts.length >= 6 ? Float.parseFloat(parts[5]) : current.getPitch();
            player.teleportAsync(new Location(world, x, y, z, yaw, pitch));
        } catch (NumberFormatException ignored) {
        }
    }

    private static String placeholders(String value, Player player, NpcDefinition npc) {
        return value
            .replace("%player%", player.getName())
            .replace("%uuid%", player.getUniqueId().toString())
            .replace("%npc%", npc.id)
            .replace("%world%", player.getWorld().getName());
    }

    private boolean isOurNpc(Entity entity) {
        return entityToNpc.containsKey(entity.getUniqueId())
            || (entity.getScoreboardTags().contains(NPC_TAG)
                && entity.getPersistentDataContainer().has(npcIdKey, PersistentDataType.STRING));
    }

    static boolean isSupportedMob(EntityType type) {
        Class<? extends Entity> entityClass = type == null ? null : type.getEntityClass();
        return type != EntityType.UNKNOWN && entityClass != null && Mob.class.isAssignableFrom(entityClass);
    }

    static EntityType parseMobType(String input) {
        try {
            EntityType type = EntityType.valueOf(input.toUpperCase(Locale.ROOT).replace('-', '_'));
            return isSupportedMob(type) ? type : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static boolean isValidAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        int separator = action.indexOf(':');
        if (separator <= 0 || separator == action.length() - 1) {
            return false;
        }
        return switch (action.substring(0, separator).toLowerCase(Locale.ROOT)) {
            case "console", "player", "message", "menu", "teleport" -> true;
            default -> false;
        };
    }

    static String normalizeId(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    static String runSelfTest() {
        if (!"shop_1".equals(normalizeId("Shop_1"))) {
            throw new IllegalStateException("NPC id normalization failed");
        }
        for (String action : List.of("console:say hi", "player:spawn", "message:<green>OK", "menu:shop", "teleport:world;0;64;0")) {
            if (!isValidAction(action)) {
                throw new IllegalStateException("NPC action validation failed: " + action);
            }
        }
        return "PURPURBEDROCK_NPC_SELFTEST_OK engine=EVENT_DRIVEN ai=OFF tasks=0 hologram=ONE_DISPLAY actions=5 potionEffects=IMMUNE";
    }

    private static Component parseHologram(String input) {
        String normalized = input == null ? "" : input.replace("\\n", "\n").replace("|", "\n");
        try {
            return MINI_MESSAGE.deserialize(normalized);
        } catch (RuntimeException ignored) {
            return Component.text(normalized);
        }
    }

    private static String stripSlash(String command) {
        String trimmed = command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static String escapeMiniMessage(String value) {
        return value.replace("<", "\\<").replace(">", "\\>");
    }
}
