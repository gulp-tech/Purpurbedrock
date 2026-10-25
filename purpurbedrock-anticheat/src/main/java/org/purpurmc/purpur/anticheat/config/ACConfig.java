package org.purpurmc.purpur.anticheat.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-parsed primitive configuration for zero-overhead hot-path reads.
 */
public final class ACConfig {
    public static final class CheckSettings {
        public final boolean enabled;
        public final double buffer;
        public final double decay;
        public final boolean setback;
        public final boolean cancelAction;
        public final double maxVl;
        public final List<String> punishCommands;

        public CheckSettings(
            boolean enabled,
            double buffer,
            double decay,
            boolean setback,
            boolean cancelAction,
            double maxVl,
            List<String> punishCommands
        ) {
            this.enabled = enabled;
            this.buffer = Math.max(0.5, buffer);
            this.decay = Math.max(0.05, decay);
            this.setback = setback;
            this.cancelAction = cancelAction;
            this.maxVl = maxVl;
            this.punishCommands = punishCommands != null ? List.copyOf(punishCommands) : Collections.emptyList();
        }
    }

    // Permissions
    public String permAlerts;
    public String permHologram;
    public String permMenu;
    public String permBypass;
    public String permAdmin;
    public boolean enableBypassPermission;

    // Adaptive Sleep/Wake Engine
    public int sleepSampleInterval;
    public int wakingSampleInterval;
    public double wakeUpStep;
    public double coolDownPerSecond;
    public double wakingThreshold;
    public double awakeThreshold;
    public double minServerTps;
    public int highPingLeniencyMs;
    public int cleanHistorySampleSeconds;

    // Alerts
    public boolean alertsAutoEnableOnJoin;
    public boolean consoleAlerts;
    public long alertMinIntervalMs;
    public boolean alertPlaySound;
    public String alertPrefix;
    public String alertFormat;
    public List<String> alertHover;
    public String alertClickCommand;

    // AVG Hologram
    public boolean hologramEnabled;
    public boolean hologramAutoEnableOnJoin;
    public double hologramHeightOffset;
    public double hologramViewDistanceSq;
    public int hologramUpdateTicks;
    public String hologramFormat;
    public double hologramGreenMaxAvg;
    public double hologramOrangeMaxAvg;

    // HolyWorld History Menu
    public String menuOverviewTitle;
    public String menuHistoryTitle;
    public int maxHistoryEntries;
    public Material matGreen;
    public Material matOrange;
    public Material matRed;
    public Material matBorder;
    public double orangeVlThreshold;
    public double redVlThreshold;
    public String legendGreenName;
    public List<String> legendGreenLore;
    public String legendOrangeName;
    public List<String> legendOrangeLore;
    public String legendRedName;
    public List<String> legendRedLore;

    // Specific check parameters
    public double maxReachPc;
    public double maxReachController;
    public double maxReachTouch;
    public double cancelReachAbove;
    public double hitboxMaxExpansion;
    public long multiAuraWindowMs;
    public boolean killauraThroughWalls;
    public boolean killauraOpenInventory;
    public int maxCpsPc;
    public int maxCpsTouch;
    public double minClickStdDevMs;
    public double minVelocityRatio;
    public int flyMinAirTicks;
    public int airJumpMinAirTicks;
    public double speedMaxHorizontalBase;
    public double timerMaxRateRatio;
    public long timerAllowanceBurstMs;
    public int noSlowMinUsingTicks;
    public double scaffoldMaxPlaceDistance;

    private final Map<String, CheckSettings> checkSettingsMap = new HashMap<>();

    public void reload(PurpurBedrockACPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        permAlerts = c.getString("permissions.alerts", "purpurac.alerts");
        permHologram = c.getString("permissions.hologram", "purpurac.hologram");
        permMenu = c.getString("permissions.menu", "purpurac.menu");
        permBypass = c.getString("permissions.bypass", "purpurac.bypass");
        permAdmin = c.getString("permissions.admin", "purpurac.admin");
        enableBypassPermission = c.getBoolean("permissions.enable-bypass-permission", false);

        sleepSampleInterval = Math.max(1, c.getInt("adaptive-engine.sleep-sample-interval", 4));
        wakingSampleInterval = Math.max(1, c.getInt("adaptive-engine.waking-sample-interval", 2));
        wakeUpStep = Math.clamp(c.getDouble("adaptive-engine.wake-up-step", 0.22), 0.05, 1.0);
        coolDownPerSecond = Math.clamp(c.getDouble("adaptive-engine.cool-down-per-second", 0.08), 0.01, 1.0);
        wakingThreshold = Math.clamp(c.getDouble("adaptive-engine.waking-threshold", 0.20), 0.05, 0.90);
        awakeThreshold = Math.clamp(c.getDouble("adaptive-engine.awake-threshold", 0.58), wakingThreshold + 0.05, 0.99);
        minServerTps = c.getDouble("adaptive-engine.min-server-tps", 18.0);
        highPingLeniencyMs = Math.max(80, c.getInt("adaptive-engine.high-ping-leniency-ms", 260));
        cleanHistorySampleSeconds = Math.max(8, c.getInt("adaptive-engine.clean-history-sample-seconds", 25));

        alertsAutoEnableOnJoin = c.getBoolean("alerts.auto-enable-on-join", true);
        consoleAlerts = c.getBoolean("alerts.console-alerts", true);
        alertMinIntervalMs = Math.max(100L, c.getLong("alerts.min-interval-ms", 650L));
        alertPlaySound = c.getBoolean("alerts.play-sound", true);
        alertPrefix = c.getString("alerts.prefix", "&#A855F7&lP&#EC4899&lA&#F43F5E&lC &8» ");
        alertFormat = c.getString("alerts.format", "{prefix}&f{player} &7флагнул {severity_color}{check} &8(&7VL: {severity_color}{vl}&8, &7AVG: {avg_color}{avg}&8) &8[&7{details}&8]");
        alertHover = c.getStringList("alerts.hover");
        alertClickCommand = c.getString("alerts.click-command", "/pac history {player}");

        hologramEnabled = c.getBoolean("hologram.enabled", true);
        hologramAutoEnableOnJoin = c.getBoolean("hologram.auto-enable-on-join", false);
        hologramHeightOffset = c.getDouble("hologram.height-offset", 2.38);
        double viewDist = Math.max(8.0, c.getDouble("hologram.view-distance-blocks", 28.0));
        hologramViewDistanceSq = viewDist * viewDist;
        hologramUpdateTicks = Math.max(4, c.getInt("hologram.update-interval-ticks", 10));
        hologramFormat = c.getString("hologram.format", "&8[&7AVG {avg_color}{avg} &8| &7VL {vl_color}{vl} &8| &7{ping}мс&8]");
        hologramGreenMaxAvg = c.getDouble("hologram.green-max-avg", 2.4);
        hologramOrangeMaxAvg = c.getDouble("hologram.orange-max-avg", 6.4);

        menuOverviewTitle = c.getString("menu.overview-title", "&8🛡 PurpurAC • Игроки ({count})");
        menuHistoryTitle = c.getString("menu.history-title", "&8📜 История: &0{player} &8[{avg_color}AVG {avg}&8]");
        maxHistoryEntries = Math.clamp(c.getInt("menu.max-history-entries", 72), 18, 256);
        matGreen = parseMat(c.getString("menu.materials.green"), Material.LIME_STAINED_GLASS);
        matOrange = parseMat(c.getString("menu.materials.orange"), Material.ORANGE_STAINED_GLASS);
        matRed = parseMat(c.getString("menu.materials.red"), Material.RED_STAINED_GLASS);
        matBorder = parseMat(c.getString("menu.materials.border"), Material.GRAY_STAINED_GLASS_PANE);
        orangeVlThreshold = c.getDouble("menu.thresholds.orange-vl", 3.0);
        redVlThreshold = c.getDouble("menu.thresholds.red-vl", 7.0);

        legendGreenName = c.getString("menu.legend.green-name", "&a&l🟢 ЗЕЛЁНЫЙ УРОВЕНЬ — ЧИСТО / БЕЗОПАСНО");
        legendGreenLore = c.getStringList("menu.legend.green-lore");
        legendOrangeName = c.getString("menu.legend.orange-name", "&6&l🟠 ОРАНЖЕВЫЙ УРОВЕНЬ — ПОДОЗРИТЕЛЬНО");
        legendOrangeLore = c.getStringList("menu.legend.orange-lore");
        legendRedName = c.getString("menu.legend.red-name", "&c&l🔴 КРАСНЫЙ УРОВЕНЬ — ВЫСОКИЙ РИСК (СОФТ)");
        legendRedLore = c.getStringList("menu.legend.red-lore");

        maxReachPc = c.getDouble("checks.reach.max-reach-pc", 3.35);
        maxReachController = c.getDouble("checks.reach.max-reach-controller", 3.45);
        maxReachTouch = c.getDouble("checks.reach.max-reach-touch", 4.18);
        cancelReachAbove = c.getDouble("checks.reach.cancel-hit-above", 4.55);
        hitboxMaxExpansion = c.getDouble("checks.hitbox.max-angle-expansion", 0.32);
        multiAuraWindowMs = c.getLong("checks.killaura.multi-aura-window-ms", 45L);
        killauraThroughWalls = c.getBoolean("checks.killaura.check-through-walls", true);
        killauraOpenInventory = c.getBoolean("checks.killaura.check-open-inventory", true);
        maxCpsPc = c.getInt("checks.autoclicker.max-cps-pc", 22);
        maxCpsTouch = c.getInt("checks.autoclicker.max-cps-touch", 19);
        minClickStdDevMs = c.getDouble("checks.autoclicker.min-stddev-ms", 2.2);
        minVelocityRatio = c.getDouble("checks.velocity.min-vertical-ratio", 0.15);
        flyMinAirTicks = c.getInt("checks.fly.min-air-ticks", 12);
        airJumpMinAirTicks = c.getInt("checks.airjump.min-air-ticks", 11);
        speedMaxHorizontalBase = c.getDouble("checks.speed.max-horizontal-base", 0.435);
        timerMaxRateRatio = c.getDouble("checks.timer.max-rate-ratio", 1.18);
        timerAllowanceBurstMs = c.getLong("checks.timer.allowance-burst-ms", 140L);
        noSlowMinUsingTicks = c.getInt("checks.noslow.min-using-ticks", 14);
        scaffoldMaxPlaceDistance = c.getDouble("checks.scaffold.max-place-distance", 6.6);

        checkSettingsMap.clear();
        ConfigurationSection checksSec = c.getConfigurationSection("checks");
        if (checksSec != null) {
            for (String key : checksSec.getKeys(false)) {
                ConfigurationSection s = checksSec.getConfigurationSection(key);
                if (s != null) {
                    boolean cancel = s.getBoolean("cancel-hit", s.getBoolean("cancel-place", false));
                    checkSettingsMap.put(
                        key.toLowerCase(Locale.ROOT),
                        new CheckSettings(
                            s.getBoolean("enabled", true),
                            s.getDouble("buffer", 3.0),
                            s.getDouble("decay", 0.35),
                            s.getBoolean("setback", false),
                            cancel,
                            s.getDouble("max-vl", 14.0),
                            s.getStringList("punish-commands")
                        )
                    );
                }
            }
        }
    }

    public CheckSettings check(String key) {
        CheckSettings found = checkSettingsMap.get(key.toLowerCase(Locale.ROOT));
        if (found != null) {
            return found;
        }
        return new CheckSettings(true, 3.0, 0.35, false, false, 14.0, Collections.emptyList());
    }

    private static Material parseMat(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material mat = Material.matchMaterial(raw.trim());
        return mat != null ? mat : fallback;
    }
}
