package org.purpurmc.purpur.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.purpurmc.purpur.anticheat.command.PACCommand;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.engine.AdaptiveEngine;
import org.purpurmc.purpur.anticheat.engine.AlertManager;
import org.purpurmc.purpur.anticheat.engine.CheckRunner;
import org.purpurmc.purpur.anticheat.gui.HolyHistoryMenu;
import org.purpurmc.purpur.anticheat.hologram.AvgHologramManager;
import org.purpurmc.purpur.anticheat.listener.ACListener;
import org.purpurmc.purpur.anticheat.model.HistoryEntry;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.model.SeverityColor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PurpurBedrockAC 26.2 — Standalone adaptive anti-cheat plugin tailored specifically
 * for Purpurbedrock 26.2 (Bedrock 1.21.0 — 26.52 physics, Touch/PC/Controller inputs,
 * adaptive Sleep -> Wake CPU optimization, AVG holograms, and HolyWorld-style history GUI).
 */
public final class PurpurBedrockACPlugin extends JavaPlugin {
    private final ACConfig config = new ACConfig();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private AdaptiveEngine adaptiveEngine;
    private AlertManager alertManager;
    private CheckRunner checkRunner;
    private AvgHologramManager hologramManager;
    private HolyHistoryMenu historyMenu;

    @Override
    public void onEnable() {
        this.config.reload(this);
        this.adaptiveEngine = new AdaptiveEngine(this);
        this.alertManager = new AlertManager(this);
        this.checkRunner = new CheckRunner(this);
        this.hologramManager = new AvgHologramManager(this);
        this.historyMenu = new HolyHistoryMenu(this);

        Bukkit.getPluginManager().registerEvents(new ACListener(this), this);

        PluginCommand cmd = getCommand("purpurac");
        if (cmd != null) {
            PACCommand executor = new PACCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            profile(online);
            alertManager.onPlayerJoin(online);
            hologramManager.onPlayerJoin(online);
        }

        this.adaptiveEngine.start();
        getLogger().info(
            "PurpurBedrockAC 26.2 запущен! Адаптивный спящий режим активен (13 проверок, AVG-голограмма, меню истории HolyWorld)."
        );
    }

    @Override
    public void onDisable() {
        if (this.adaptiveEngine != null) {
            this.adaptiveEngine.stop();
        }
        if (this.hologramManager != null) {
            this.hologramManager.shutdownAllDisplays();
        }
        this.profiles.clear();
    }

    public void reloadAll() {
        this.config.reload(this);
        this.hologramManager.reload();
        this.adaptiveEngine.start();
    }

    public ACConfig cfg() {
        return config;
    }

    public AdaptiveEngine engine() {
        return adaptiveEngine;
    }

    public AlertManager alerts() {
        return alertManager;
    }

    public CheckRunner checks() {
        return checkRunner;
    }

    public AvgHologramManager holograms() {
        return hologramManager;
    }

    public HolyHistoryMenu menus() {
        return historyMenu;
    }

    public PlayerProfile profile(Player player) {
        return profiles.computeIfAbsent(
            player.getUniqueId(),
            id -> new PlayerProfile(id, player.getName())
        );
    }

    public PlayerProfile profileByUuid(UUID uuid) {
        return profiles.get(uuid);
    }

    public PlayerProfile findProfileByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return profile(online);
        }
        for (PlayerProfile p : profiles.values()) {
            if (p.playerName.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    public List<String> cachedPlayerNames() {
        List<String> out = new ArrayList<>(profiles.size());
        for (PlayerProfile p : profiles.values()) {
            out.add(p.playerName);
        }
        return out;
    }

    public void trimOfflineProfilesIfNeeded() {
        if (profiles.size() <= 256) {
            return;
        }
        Iterator<Map.Entry<UUID, PlayerProfile>> it = profiles.entrySet().iterator();
        while (it.hasNext() && profiles.size() > 192) {
            Map.Entry<UUID, PlayerProfile> en = it.next();
            if (Bukkit.getPlayer(en.getKey()) == null) {
                it.remove();
            }
        }
    }

    /**
     * Built-in end-to-end verification of Adaptive Sleep/Wake, Green/Orange/Red HolyWorld GUI,
     * bottom instruction legend blocks, and zero-orphan hologram state.
     */
    public String runSelfTest() {
        UUID testId = UUID.randomUUID();
        PlayerProfile demo = new PlayerProfile(testId, "BedrockTester");
        long now = System.currentTimeMillis();
        demo.pushHistory(new HistoryEntry(now - 3000, SeverityColor.GREEN, "Чистая игра", "CPS=6 Reach=2.70", 0.0, 0.2, 32, 20.0, "📱 Телефон", "Сенсор", "Норма"), config.maxHistoryEntries);
        demo.pushHistory(new HistoryEntry(now - 2000, SeverityColor.ORANGE, "Reach", "dist=4.35 max=4.18", 4.0, 3.4, 41, 20.0, "📱 Телефон", "Сенсор", "Лог"), config.maxHistoryEntries);
        demo.pushHistory(new HistoryEntry(now - 1000, SeverityColor.RED, "Fly (Ascend)", "dy=0.42 air=16t", 8.0, 7.2, 41, 20.0, "📱 Телефон", "Сенсор", "Сетбэк"), config.maxHistoryEntries);

        engine().nudgeSuspicion(demo, 1.0);
        engine().nudgeSuspicion(demo, 2.0);

        Inventory overview = historyMenu.buildOverviewInventory(null, 0);
        Inventory histInv = historyMenu.buildHistoryInventory(null, demo, 0);

        if (overview.getSize() != 54 || histInv.getSize() != 54) {
            throw new IllegalStateException("Expected 54-slot HolyWorld inventory");
        }
        // Verify slots 9, 10, 11 contain Red (newest), Orange, Green history items
        ItemStack newestRed = histInv.getItem(9);
        ItemStack midOrange = histInv.getItem(10);
        ItemStack oldestGreen = histInv.getItem(11);
        if (newestRed == null || newestRed.getType() != config.matRed
            || midOrange == null || midOrange.getType() != config.matOrange
            || oldestGreen == null || oldestGreen.getType() != config.matGreen) {
            throw new IllegalStateException("History color blocks mismatch in slots 9..11");
        }
        // Verify bottom instruction legend blocks in slots 46 (Green), 47 (Orange), 48 (Red)
        ItemStack legGreen = histInv.getItem(46);
        ItemStack legOrange = histInv.getItem(47);
        ItemStack legRed = histInv.getItem(48);
        if (legGreen == null || legGreen.getType() != config.matGreen
            || legOrange == null || legOrange.getType() != config.matOrange
            || legRed == null || legRed.getType() != config.matRed) {
            throw new IllegalStateException("Bottom HolyWorld instruction blocks missing in slots 46..48");
        }
        if (hologramManager.getActiveEntityCount() != 0) {
            throw new IllegalStateException("Idle hologram entity count must be 0");
        }
        return "PURPURBEDROCK_AC_SELFTEST_OK gui=54 legend=GREEN_ORANGE_RED stage=" + demo.stage.name() + " idleHolo=0";
    }
}
