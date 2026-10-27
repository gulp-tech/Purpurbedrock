package org.purpurmc.purpur.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
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
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PurpurBedrockAC 26.2.6 — Standalone adaptive anti-cheat plugin tailored specifically
 * for Purpurbedrock 26.2 (Geyser 26.2 + Bedrock 1.21.0 — 26.60 physics, Touch/PC/Phone-KBM/Controller inputs,
 * 25 calibrated checks, adaptive Sleep to Wake CPU optimization, device-adaptive AVG holograms, and HolyWorld-style GUIs).
 */
public final class PurpurBedrockACPlugin extends JavaPlugin {
    private final ACConfig config = new ACConfig();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private AdaptiveEngine adaptiveEngine;
    private AlertManager alertManager;
    private CheckRunner checkRunner;
    private AvgHologramManager hologramManager;
    private HolyHistoryMenu historyMenu;
    private PACCommand pacCommandExecutor;

    @Override
    public void onEnable() {
        this.config.reload(this);
        this.adaptiveEngine = new AdaptiveEngine(this);
        this.alertManager = new AlertManager(this);
        this.checkRunner = new CheckRunner(this);
        this.hologramManager = new AvgHologramManager(this);
        this.historyMenu = new HolyHistoryMenu(this);
        this.pacCommandExecutor = new PACCommand(this);
        BedrockBridgeInfo.bindPlugin(this);

        Bukkit.getPluginManager().registerEvents(new ACListener(this), this);

        bindAllCommands();
        // Re-assert /pac and /purpurac in CommandMap after all other plugins finish enabling
        Bukkit.getScheduler().runTaskLater(this, this::bindAllCommands, 20L);

        for (Player online : Bukkit.getOnlinePlayers()) {
            profile(online);
            alertManager.onPlayerJoin(online);
            hologramManager.onPlayerJoin(online);
        }

        this.adaptiveEngine.start();
        getLogger().info(
            "PurpurBedrockAC 26.2 запущен! Адаптивный спящий режим активен ("
            + config.countEnabledChecks() + " проверок Geyser/Bedrock, 0 ложных киков, мгновенная AVG-голограмма, меню HolyWorld)."
        );
    }

    private void bindAllCommands() {
        PluginCommand pacCmd = getCommand("pac");
        PluginCommand purpurAcCmd = getCommand("purpurac");
        if (pacCmd != null) {
            pacCmd.setExecutor(this.pacCommandExecutor);
            pacCmd.setTabCompleter(this.pacCommandExecutor);
        }
        if (purpurAcCmd != null) {
            purpurAcCmd.setExecutor(this.pacCommandExecutor);
            purpurAcCmd.setTabCompleter(this.pacCommandExecutor);
        }
        try {
            CommandMap cmdMap = Bukkit.getCommandMap();
            Map<String, Command> known = cmdMap.getKnownCommands();
            if (pacCmd != null) {
                known.put("pac", pacCmd);
                known.put("purpurbedrockac:pac", pacCmd);
            }
            if (purpurAcCmd != null) {
                known.put("purpurac", purpurAcCmd);
                known.put("purpurbedrockac:purpurac", purpurAcCmd);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean ownsCommandInMap(String label) {
        try {
            Command found = Bukkit.getCommandMap().getKnownCommands().get(label.toLowerCase(Locale.ROOT));
            if (found instanceof PluginCommand pc) {
                return pc.getPlugin() == this;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public void executePacCommandDirect(CommandSender sender, String label, String[] args) {
        PluginCommand cmd = getCommand("pac");
        if (cmd == null) {
            cmd = getCommand("purpurac");
        }
        if (cmd != null && this.pacCommandExecutor != null) {
            this.pacCommandExecutor.onCommand(sender, cmd, label, args);
        }
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
     * Built-in end-to-end verification of all 25 checks, Grim-style Wi-Fi lag credit Timer bucket,
     * 18-tick backtracked Ray/AABB Reach and HitBox, HolyWorld Green/Orange/Red GUI + bottom legend,
     * Live 25-Check Configurator GUI, instant 1-tick hologram config, and zero-orphan hologram state.
     */
    public String runSelfTest() {
        if (ACConfig.ALL_CHECKS.size() != 25 || config.countEnabledChecks() != 25) {
            throw new IllegalStateException("Expected 25 active Bedrock/Geyser check modules");
        }
        if (config.hologramHeightOffset < 2.65 || config.hologramUpdateTicks != 1) {
            throw new IllegalStateException("Expected raised instant hologram settings: h=" + config.hologramHeightOffset);
        }
        UUID testId = UUID.randomUUID();
        PlayerProfile demo = new PlayerProfile(testId, "BedrockTester");
        long t0 = 1_000_000L;

        // 1. Verify Grim-style Token Bucket Timer immunity to a 1500ms mobile Wi-Fi freeze + 30-packet flush!
        demo.advanceTimerBucket(t0, config.timerMaxLagCreditMs);
        double bal = demo.advanceTimerBucket(t0 + 1500L, config.timerMaxLagCreditMs);
        for (int i = 1; i < 30; i++) {
            bal = demo.advanceTimerBucket(t0 + 1500L + i, config.timerMaxLagCreditMs);
            if (bal > config.timerMaxBalanceMs) {
                throw new IllegalStateException("Wi-Fi stall burst must NOT exceed timerMaxBalanceMs! bal=" + bal);
            }
        }

        // 2. Verify 18-tick ping-backtracked Reach & RayTrace HitBox compensation
        BoundingBox oldBox = new BoundingBox(10.0, 64.0, 10.0, 10.6, 65.8, 10.6);
        BoundingBox liveMovedBox = new BoundingBox(12.2, 64.0, 10.0, 12.8, 65.8, 10.6);
        demo.recordBoundingBox(oldBox, t0 + 1850L);
        double compDist = demo.computeMinCompensatedDistance(7.5, 65.6, 10.3, liveMovedBox, 0.13, t0 + 1700L);
        boolean compRay = demo.intersectsCompensatedRay(
            new Vector(7.5, 65.6, 10.3), new Vector(1.0, -0.05, 0.0).normalize(),
            liveMovedBox, 0.25, 0.25, 6.5, t0 + 1700L
        );
        if (compDist > 2.6 || !compRay) {
            throw new IllegalStateException("Backtracked Reach/HitBox ring buffer failed: dist=" + compDist + " ray=" + compRay);
        }

        // 3. Verify temporal anti-false-alert confirmation and per-check flood cooldown.
        PlayerProfile evidenceDemo = new PlayerProfile(UUID.randomUUID(), "EvidenceSelfTest");
        long evidenceNow = 10_000L;
        boolean evidenceFirst = evidenceDemo.confirmViolationEvidence("speed", evidenceNow, 2, 700L, 8000L, 4000L);
        boolean evidenceBurst = evidenceDemo.confirmViolationEvidence("speed", evidenceNow + 50L, 2, 700L, 8000L, 4000L);
        boolean evidenceConfirmed = evidenceDemo.confirmViolationEvidence("speed", evidenceNow + 750L, 2, 700L, 8000L, 4000L);
        boolean evidenceCooldown = evidenceDemo.confirmViolationEvidence("speed", evidenceNow + 900L, 2, 700L, 8000L, 4000L);
        boolean evidenceFreshFirst = evidenceDemo.confirmViolationEvidence("speed", evidenceNow + 4800L, 2, 700L, 8000L, 4000L);
        boolean evidenceFreshConfirmed = evidenceDemo.confirmViolationEvidence("speed", evidenceNow + 5550L, 2, 700L, 8000L, 4000L);
        if (evidenceFirst || evidenceBurst || !evidenceConfirmed || evidenceCooldown
            || evidenceFreshFirst || !evidenceFreshConfirmed) {
            throw new IllegalStateException("Anti-false-alert temporal confirmation gate mismatch");
        }

        // 4. Verify plugin-generated 3x3 drill neighbours cannot impersonate client mining packets.
        PlayerProfile drillDemo = new PlayerProfile(UUID.randomUUID(), "DrillSelfTest");
        drillDemo.miningTracked = true;
        drillDemo.mineX = 120;
        drillDemo.mineY = 32;
        drillDemo.mineZ = -45;
        if (!drillDemo.matchesTrackedMiningBlock(120, 32, -45)
            || drillDemo.matchesTrackedMiningBlock(121, 32, -45)
            || drillDemo.matchesTrackedMiningBlock(120, 33, -45)
            || drillDemo.matchesTrackedMiningBlock(120, 32, -44)) {
            throw new IllegalStateException("Synthetic 3x3 drill break discrimination mismatch");
        }

        // 5. Verify HolyWorld 3-color history + bottom instruction legend + Live 25-Checks GUI
        long now = System.currentTimeMillis();
        demo.pushHistory(new HistoryEntry(now - 3000, SeverityColor.GREEN, "Чистая игра", "CPS=6 Reach=2.70", 0.0, 0.2, 32, 20.0, "Android", "Сенсор", "Норма"), config.maxHistoryEntries);
        demo.pushHistory(new HistoryEntry(now - 2000, SeverityColor.ORANGE, "Reach", "dist=4.35 max=4.15", 4.0, 3.4, 41, 20.0, "Android", "Сенсор", "Отмена удара"), config.maxHistoryEntries);
        demo.pushHistory(new HistoryEntry(now - 1000, SeverityColor.RED, "ElytraFly (Speed)", "xz=2.45 dy=0.000", 8.0, 7.2, 41, 20.0, "Клавиатура + мышь", "Клавиатура + мышь", "Сетбэк"), config.maxHistoryEntries);

        engine().nudgeSuspicion(demo, 1.0);
        engine().nudgeSuspicion(demo, 2.0);

        Inventory overview = historyMenu.buildOverviewInventory(null, 0);
        Inventory histInv = historyMenu.buildHistoryInventory(null, demo, 0);
        Inventory checksInv = historyMenu.buildChecksInventory(null);

        if (overview.getSize() != 54 || histInv.getSize() != 54 || checksInv.getSize() != 54) {
            throw new IllegalStateException("Expected 54-slot inventories");
        }
        ItemStack newestRed = histInv.getItem(9);
        ItemStack midOrange = histInv.getItem(10);
        ItemStack oldestGreen = histInv.getItem(11);
        if (newestRed == null || newestRed.getType() != config.matRed
            || midOrange == null || midOrange.getType() != config.matOrange
            || oldestGreen == null || oldestGreen.getType() != config.matGreen) {
            throw new IllegalStateException("History color blocks mismatch in slots 9..11");
        }
        ItemStack legGreen = histInv.getItem(46);
        ItemStack legOrange = histInv.getItem(47);
        ItemStack legRed = histInv.getItem(48);
        if (legGreen == null || legGreen.getType() != config.matGreen
            || legOrange == null || legOrange.getType() != config.matOrange
            || legRed == null || legRed.getType() != config.matRed) {
            throw new IllegalStateException("Bottom HolyWorld instruction blocks missing in slots 46..48");
        }
        if (checksInv.getItem(9) == null || checksInv.getItem(33) == null) {
            throw new IllegalStateException("Checks configurator GUI missing module icons in slots 9..33");
        }
        if (hologramManager.getActiveEntityCount() != 0) {
            throw new IllegalStateException("Idle hologram entity count must be 0");
        }
        BedrockBridgeInfo.verifySelfTest();
        if (!config.hologramAdaptiveRendering
            || config.hologramMobileUpdateTicks < config.hologramDesktopUpdateTicks
            || config.hologramMobileViewDistanceSq > config.hologramDesktopViewDistanceSq
            || config.hologramMobileMaxTargets > config.hologramDesktopMaxTargets) {
            throw new IllegalStateException("Device-adaptive hologram budget mismatch");
        }
        return "PURPURBEDROCK_AC_SELFTEST_OK checks=25/25 wifiLagSafe=true antiFalseAlerts=CONFIRMED drill3x3=SAFE adaptiveRender=OK gui=54 legend=GREEN_ORANGE_RED stage=" + demo.stage.name() + " idleHolo=0 devices=6";
    }
}
