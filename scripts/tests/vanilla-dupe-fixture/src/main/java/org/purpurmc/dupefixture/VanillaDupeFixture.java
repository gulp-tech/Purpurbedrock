package org.purpurmc.dupefixture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable test fixture: never included in the product. Runs only on an isolated server. */
public final class VanillaDupeFixture extends JavaPlugin implements Listener {
    private World world;
    private int x, y, z, extensions;
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::start, 80L);
    }
    private void set(int dx, int dy, int dz, String state) {
        Block b = world.getBlockAt(x + dx, y + dy, z + dz);
        b.setBlockData(Bukkit.createBlockData(state), false);
    }
    @EventHandler public void onPiston(BlockPistonExtendEvent event) {
        Block b = event.getBlock();
        if (world != null && b.getWorld().equals(world) && b.getX() == x && b.getY() == y && b.getZ() == z) extensions++;
    }
    private void start() {
        try {
            var global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration").getMethod("get").invoke(null);
            var unsupported = global.getClass().getField("unsupportedSettings").get(global);
            boolean allow = unsupported.getClass().getField("allowPistonDuplication").getBoolean(unsupported);
            if (allow != Boolean.parseBoolean(System.getenv("DUPE_TEST_ALLOW"))) {
                throw new IllegalStateException("Piston option did not load expected value");
            }
            world = Bukkit.getWorlds().getFirst();
            Location spawn = world.getSpawnLocation();
            x = spawn.getBlockX(); z = spawn.getBlockZ(); y = Math.min(100, world.getMaxHeight() - 8);
            world.getChunkAt(x >> 4, z >> 4).setForceLoaded(true);
            // Clear a small isolated airspace, with no floor touching the slime.
            for (int dx = -2; dx <= 5; dx++) for (int dy = -2; dy <= 5; dy++) for (int dz = -2; dz <= 4; dz++)
                world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
            set(0, 0, 0, "minecraft:sticky_piston[facing=east,extended=false]");
            set(1, 0, 0, "minecraft:slime_block");
            set(1, 1, 0, "minecraft:slime_block");
            set(1, 0, 1, "minecraft:dead_tube_coral_wall_fan[facing=south]");
            set(1, 1, 1, "minecraft:white_carpet");
            pulse(0);
        } catch (Throwable e) {
            getLogger().severe("DUPE_TEST_ERROR " + e);
            e.printStackTrace();
        }
    }
    private void pulse(int cycle) {
        if (cycle == 8) {
            Bukkit.getScheduler().runTaskLater(this, this::report, 20L);
            return;
        }
        world.getBlockAt(x - 1, y, z).setType(Material.REDSTONE_BLOCK, true);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            world.getBlockAt(x - 1, y, z).setType(Material.AIR, true);
            Bukkit.getScheduler().runTaskLater(this, () -> pulse(cycle + 1), 6L);
        }, 6L);
    }
    private void report() {
        int blocks = 0;
        for (int dx = -2; dx <= 5; dx++) for (int dy = -2; dy <= 5; dy++) for (int dz = -2; dz <= 4; dz++)
            if (world.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.WHITE_CARPET) blocks++;
        int items = world.getEntitiesByClass(Item.class).stream()
                .filter(item -> item.getItemStack().getType() == Material.WHITE_CARPET)
                .filter(item -> item.getLocation().distanceSquared(new Location(world, x, y, z)) < 10000)
                .mapToInt(item -> item.getItemStack().getAmount()).sum();
        getLogger().info("DUPE_TEST_REPORT allow=" + System.getenv("DUPE_TEST_ALLOW") + " extensions=" + extensions + " blocks=" + blocks + " items=" + items + " total=" + (blocks + items));
    }
}
