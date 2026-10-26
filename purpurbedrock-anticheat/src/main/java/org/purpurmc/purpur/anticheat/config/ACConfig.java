package org.purpurmc.purpur.anticheat.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-parsed primitive configuration for zero-overhead hot-path reads,
 * automatic config migration, and live in-game check toggle persistence to config.yml.
 */
public final class ACConfig {

    public record CheckMeta(String key, String title, Material icon, String shortDesc, boolean movementStyle) {
    }

    public static final List<CheckMeta> ALL_CHECKS = List.of(
        new CheckMeta("reach", "Reach (Дальность удара)", Material.DIAMOND_SWORD, "3D AABB + откат позиций цели на 18 тиков под пинг", false),
        new CheckMeta("hitbox", "HitBox (Хитбоксы)", Material.SPECTRAL_ARROW, "Луч прицела на ПК/КМ/Геймпаде + конус экрана на Сенсоре", false),
        new CheckMeta("killaura", "KillAura (Мульти/Стены/360°)", Material.NETHERITE_SWORD, "MultiAura, удар спиной, сквозь плотные стены и с сундуком", false),
        new CheckMeta("aim", "Aim / SnapRotations (Аимбот)", Material.SPYGLASS, "Ловит резкие SilentAim-довороты и роботизированный AimLock", false),
        new CheckMeta("autoclicker", "AutoClicker / Макросы", Material.CLOCK, "Лимит боевых CPS + анализ разброса интервалов макроса", false),
        new CheckMeta("criticals", "Criticals (Пакетные криты)", Material.IRON_AXE, "Блокирует микро-подскоки Packet/EC Criticals без прыжка", false),
        new CheckMeta("velocity", "Velocity (Anti-Knockback)", Material.PISTON, "Динамическое окно отдачи с учётом пинга, потолка и воды", false),
        new CheckMeta("autototem", "AutoTotem (Авто-Тотем)", Material.TOTEM_OF_UNDYING, "Блокирует мгновенный свап тотема < 65 мс и на бегу", false),
        new CheckMeta("fastbow", "FastBow (Пулемёт из лука)", Material.BOW, "Блокирует мгновенный натяг лука быстрее физики ванили", false),
        new CheckMeta("fly", "Fly / MotionFly / Glide", Material.FEATHER, "Закон гравитации (dy - lastDy) без ложных флагов на слизи", true),
        new CheckMeta("elytrafly", "ElytraFly / PacketElytra", Material.ELYTRA, "Ловит чит-полёт и разгон на элитрах без фейерверков", true),
        new CheckMeta("airjump", "AirJump (Прыжки в воздухе)", Material.RABBIT_FOOT, "Ловит прыжки по воздуху без опоры и ветровых зарядов", true),
        new CheckMeta("speed", "Speed / BHop / Strafe", Material.SUGAR, "Физика скорости с учётом льда, зелий, элитр и трезубца", true),
        new CheckMeta("step", "Step (Высокий шаг > 1 бл.)", Material.QUARTZ_STAIRS, "Разрешает авто-шаг Bedrock 0.6, ловит Step 1.1..5.0", true),
        new CheckMeta("highjump", "HighJump / JetPack", Material.SLIME_BLOCK, "Ловит завышенный вертикальный импульс прыжка", true),
        new CheckMeta("spider", "Spider (Лазание по стенам)", Material.COBWEB, "Ловит подъём по плоским стенам без лестниц и лиан", true),
        new CheckMeta("jesus", "Jesus (Хождение по воде)", Material.WATER_BUCKET, "Проверка 4 углов хитбокса, лодок, кувшинок и полублоков", true),
        new CheckMeta("noslow", "NoSlow (Бег с едой/луком)", Material.GOLDEN_APPLE, "Сброс при смене слота + игнор авто-щита при приседе", true),
        new CheckMeta("nofall", "NoFall / ElytraGlitch", Material.HAY_BLOCK, "Ловит фейк-полёт элитрами без элитр и GroundSpoof", true),
        new CheckMeta("phase", "Phase / NoClip / VClip", Material.ENDER_PEARL, "Блокирует проход сквозь плотные стены, пол и потолок", true),
        new CheckMeta("timer", "Timer / Blink (Токен-бакет)", Material.COMPASS, "Кредит лагов Wi-Fi до 3.5 сек — 0 ложных флагов на телефоне", true),
        new CheckMeta("scaffold", "Scaffold / AirPlace / Tower", Material.SANDSTONE, "Разрешает спид-бридж Bedrock, ловит AirPlace и быстрый Tower", false),
        new CheckMeta("fastbreak", "FastBreak / Nuker / BedFucker", Material.NETHERITE_PICKAXE, "Точная формула NMS getBreakSpeed + защита кроватей сквозь блоки", false),
        new CheckMeta("inventory", "ChestStealer / InvMove", Material.CHEST, "Авто-лут сундуков < 32 мс и спринт с открытым контейнером", false),
        new CheckMeta("badpackets", "BadPackets (Пакеты / Pitch)", Material.BARRIER, "Невозможный угол > 91°, NaN-координаты и удар по себе", true)
    );

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

    // Anti-False-Kick & Global Mitigation Safety
    public boolean autoPunishEnabled;
    public int maxPingForAutoPunish;
    public double minTpsForAutoPunish;
    public long setbackCooldownMs;

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
    public boolean alertConfirmationEnabled;
    public int alertEvidenceRequired;
    public long alertEvidenceMinSeparationMs;
    public long alertEvidenceWindowMs;
    public long alertSameCheckCooldownMs;
    public int alertHighPingExtraEvidence;
    public boolean alertPlaySound;
    public String alertPrefix;
    public String alertFormat;
    public List<String> alertHover;
    public String alertClickCommand;

    // AVG Hologram
    public boolean hologramEnabled;
    public boolean hologramAutoEnableOnJoin;
    public double hologramHeightOffset;
    public double hologramVelocityLead;
    public double hologramViewDistanceSq;
    public int hologramUpdateTicks;
    public String hologramFormat;
    public double hologramGreenMaxAvg;
    public double hologramOrangeMaxAvg;

    // HolyWorld History & Checks Menus
    public String menuOverviewTitle;
    public String menuHistoryTitle;
    public String menuChecksTitle;
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

    // Calibrated Physics & Combat Check Parameters
    public double maxReachPc;
    public double maxReachController;
    public double maxReachTouch;
    public double cancelReachAbove;
    public double hitboxMaxExpansion;
    public double hitboxTouchMaxAngleDeg;
    public long multiAuraWindowMs;
    public boolean killauraThroughWalls;
    public boolean killauraOpenInventory;
    public float aimSnapMinYawDeg;
    public float aimSnapBackMinYawDeg;
    public int maxCpsPc;
    public int maxCpsTouch;
    public double minClickStdDevMs;
    public double minVelocityRatio;
    public long autoTotemMinPopDelayMs;
    public long fastBowMinFullDrawMs;
    public double elytraMaxNoBoostSpeed;
    public int elytraHoverMinTicks;
    public int flyMinAirTicks;
    public int airJumpMinAirTicks;
    public double speedMaxHorizontalBase;
    public double stepMaxHeight;
    public double highJumpMaxBase;
    public int spiderMinWallTicks;
    public int noSlowMinUsingTicks;
    public int noFallMinAirTicks;
    public double timerMaxBalanceMs;
    public double timerMaxLagCreditMs;
    public long blinkMinFreezeMs;
    public double blinkMaxBurstDistance;
    public double scaffoldMaxPlaceDistance;
    public long towerMinBlockIntervalMs;
    public double fastBreakMinTimeRatio;
    public int nukerMaxBlocksPerSec;
    public boolean bedFuckerThroughWalls;
    public double chestStealerMinAvgMs;
    public int invMoveMinTicks;

    private final Map<String, CheckSettings> checkSettingsMap = new LinkedHashMap<>();

    public void reload(PurpurBedrockACPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        // Auto-migrate older v1/v2 config.yml hologram speed & height once so existing servers get instant 1-tick + 2.76m height
        int cfgVersion = c.getInt("config-version", 1);
        boolean migrated = false;
        if (cfgVersion < 3) {
            if (c.getDouble("hologram.height-offset", 2.38) <= 2.45) {
                c.set("hologram.height-offset", 2.76);
            }
            if (c.getInt("hologram.update-interval-ticks", 10) > 1) {
                c.set("hologram.update-interval-ticks", 1);
            }
            if (!c.contains("hologram.velocity-lead")) {
                c.set("hologram.velocity-lead", 0.45);
            }
            String oldMenuTitle = c.getString("menu.checks-title", "");
            if (oldMenuTitle.contains("21 проверки")) {
                c.set("menu.checks-title", "&8⚙ PurpurAC • Настройка 25 проверок");
            }
            c.set("config-version", 3);
            migrated = true;
        }
        // v4 adds a temporal confirmation gate. A single Bedrock/Geyser interpolation spike is no longer
        // written to history or broadcast as a staff complaint; real cheats repeat and confirm quickly.
        if (cfgVersion < 4) {
            if (!c.contains("alerts.confirmation.enabled")) {
                c.set("alerts.confirmation.enabled", true);
                c.set("alerts.confirmation.evidence-required", 2);
                c.set("alerts.confirmation.min-separation-ms", 700L);
                c.set("alerts.confirmation.window-ms", 8000L);
                c.set("alerts.confirmation.same-check-cooldown-ms", 4000L);
                c.set("alerts.confirmation.high-ping-extra-evidence", 1);
            }
            c.set("config-version", 4);
            migrated = true;
        }
        if (migrated) {
            plugin.saveConfig();
        }

        permAlerts = c.getString("permissions.alerts", "purpurac.alerts");
        permHologram = c.getString("permissions.hologram", "purpurac.hologram");
        permMenu = c.getString("permissions.menu", "purpurac.menu");
        permBypass = c.getString("permissions.bypass", "purpurac.bypass");
        permAdmin = c.getString("permissions.admin", "purpurac.admin");
        enableBypassPermission = c.getBoolean("permissions.enable-bypass-permission", false);

        autoPunishEnabled = c.getBoolean("protection.auto-punish-enabled", false);
        maxPingForAutoPunish = Math.max(120, c.getInt("protection.max-ping-for-autopunish", 320));
        minTpsForAutoPunish = Math.clamp(c.getDouble("protection.min-tps-for-autopunish", 18.5), 12.0, 20.0);
        setbackCooldownMs = Math.max(150L, c.getLong("protection.setback-cooldown-ms", 450L));

        sleepSampleInterval = Math.max(1, c.getInt("adaptive-engine.sleep-sample-interval", 4));
        wakingSampleInterval = Math.max(1, c.getInt("adaptive-engine.waking-sample-interval", 2));
        wakeUpStep = Math.clamp(c.getDouble("adaptive-engine.wake-up-step", 0.22), 0.05, 1.0);
        coolDownPerSecond = Math.clamp(c.getDouble("adaptive-engine.cool-down-per-second", 0.08), 0.01, 1.0);
        wakingThreshold = Math.clamp(c.getDouble("adaptive-engine.waking-threshold", 0.20), 0.05, 0.90);
        awakeThreshold = Math.clamp(c.getDouble("adaptive-engine.awake-threshold", 0.58), wakingThreshold + 0.05, 0.99);
        minServerTps = c.getDouble("adaptive-engine.min-server-tps", 18.0);
        highPingLeniencyMs = Math.max(80, c.getInt("adaptive-engine.high-ping-leniency-ms", 250));
        cleanHistorySampleSeconds = Math.max(8, c.getInt("adaptive-engine.clean-history-sample-seconds", 25));

        alertsAutoEnableOnJoin = c.getBoolean("alerts.auto-enable-on-join", true);
        consoleAlerts = c.getBoolean("alerts.console-alerts", true);
        alertMinIntervalMs = Math.max(100L, c.getLong("alerts.min-interval-ms", 650L));
        alertConfirmationEnabled = c.getBoolean("alerts.confirmation.enabled", true);
        alertEvidenceRequired = Math.clamp(c.getInt("alerts.confirmation.evidence-required", 2), 1, 5);
        alertEvidenceMinSeparationMs = Math.clamp(c.getLong("alerts.confirmation.min-separation-ms", 700L), 100L, 5000L);
        alertEvidenceWindowMs = Math.max(alertEvidenceMinSeparationMs,
            c.getLong("alerts.confirmation.window-ms", 8000L));
        alertSameCheckCooldownMs = Math.max(500L,
            c.getLong("alerts.confirmation.same-check-cooldown-ms", 4000L));
        alertHighPingExtraEvidence = Math.clamp(c.getInt("alerts.confirmation.high-ping-extra-evidence", 1), 0, 3);
        alertPlaySound = c.getBoolean("alerts.play-sound", true);
        alertPrefix = c.getString("alerts.prefix", "&#A855F7&lP&#EC4899&lA&#F43F5E&lC &8» ");
        alertFormat = c.getString("alerts.format", "{prefix}&f{player} &7флагнул {severity_color}{check} &8(&7VL: {severity_color}{vl}&8, &7AVG: {avg_color}{avg}&8) &8[&7{details}&8]");
        alertHover = c.getStringList("alerts.hover");
        alertClickCommand = c.getString("alerts.click-command", "/pac history {player}");

        hologramEnabled = c.getBoolean("hologram.enabled", true);
        hologramAutoEnableOnJoin = c.getBoolean("hologram.auto-enable-on-join", false);
        hologramHeightOffset = Math.clamp(c.getDouble("hologram.height-offset", 2.76), 1.8, 5.0);
        hologramVelocityLead = Math.clamp(c.getDouble("hologram.velocity-lead", 0.45), 0.0, 1.2);
        double viewDist = Math.max(8.0, c.getDouble("hologram.view-distance-blocks", 32.0));
        hologramViewDistanceSq = viewDist * viewDist;
        hologramUpdateTicks = Math.max(1, c.getInt("hologram.update-interval-ticks", 1));
        hologramFormat = c.getString("hologram.format", "&8[&7AVG {avg_color}{avg} &8| &7VL {vl_color}{vl} &8| &7{ping}мс&8]");
        hologramGreenMaxAvg = c.getDouble("hologram.green-max-avg", 2.4);
        hologramOrangeMaxAvg = c.getDouble("hologram.orange-max-avg", 6.4);

        menuOverviewTitle = c.getString("menu.overview-title", "&8🛡 PurpurAC • Игроки ({count})");
        menuHistoryTitle = c.getString("menu.history-title", "&8📜 История: &0{player} &8[{avg_color}AVG {avg}&8]");
        menuChecksTitle = c.getString("menu.checks-title", "&8⚙ PurpurAC • Настройка 25 проверок");
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

        maxReachPc = c.getDouble("checks.reach.max-reach-pc", 3.30);
        maxReachController = c.getDouble("checks.reach.max-reach-controller", 3.40);
        maxReachTouch = c.getDouble("checks.reach.max-reach-touch", 4.15);
        cancelReachAbove = c.getDouble("checks.reach.cancel-hit-above", 4.45);

        hitboxMaxExpansion = c.getDouble("checks.hitbox.max-angle-expansion", 0.30);
        hitboxTouchMaxAngleDeg = c.getDouble("checks.hitbox.touch-max-angle-deg", 88.0);

        multiAuraWindowMs = c.getLong("checks.killaura.multi-aura-window-ms", 45L);
        killauraThroughWalls = c.getBoolean("checks.killaura.check-through-walls", true);
        killauraOpenInventory = c.getBoolean("checks.killaura.check-open-inventory", true);

        aimSnapMinYawDeg = (float) c.getDouble("checks.aim.snap-min-yaw-deg", 58.0);
        aimSnapBackMinYawDeg = (float) c.getDouble("checks.aim.snap-back-min-yaw-deg", 45.0);

        maxCpsPc = c.getInt("checks.autoclicker.max-cps-pc", 22);
        maxCpsTouch = c.getInt("checks.autoclicker.max-cps-touch", 19);
        minClickStdDevMs = c.getDouble("checks.autoclicker.min-stddev-ms", 2.0);

        minVelocityRatio = c.getDouble("checks.velocity.min-vertical-ratio", 0.14);
        autoTotemMinPopDelayMs = c.getLong("checks.autototem.min-pop-delay-ms", 65L);
        fastBowMinFullDrawMs = c.getLong("checks.fastbow.min-full-draw-ms", 620L);
        elytraMaxNoBoostSpeed = c.getDouble("checks.elytrafly.max-noboost-speed", 1.95);
        elytraHoverMinTicks = c.getInt("checks.elytrafly.min-hover-ticks", 12);

        flyMinAirTicks = c.getInt("checks.fly.min-air-ticks", 12);
        airJumpMinAirTicks = c.getInt("checks.airjump.min-air-ticks", 11);
        speedMaxHorizontalBase = c.getDouble("checks.speed.max-horizontal-base", 0.44);
        stepMaxHeight = c.getDouble("checks.step.max-step-height", 1.05);
        highJumpMaxBase = c.getDouble("checks.highjump.max-jump-dy", 0.56);
        spiderMinWallTicks = c.getInt("checks.spider.min-wall-ticks", 9);
        noSlowMinUsingTicks = c.getInt("checks.noslow.min-using-ticks", 15);
        noFallMinAirTicks = c.getInt("checks.nofall.min-air-ticks", 10);

        timerMaxBalanceMs = c.getDouble("checks.timer.max-balance-ms", 260.0);
        timerMaxLagCreditMs = c.getDouble("checks.timer.max-lag-credit-ms", 3500.0);
        blinkMinFreezeMs = c.getLong("checks.timer.blink-min-freeze-ms", 1600L);
        blinkMaxBurstDistance = c.getDouble("checks.timer.blink-max-burst-blocks", 9.5);

        scaffoldMaxPlaceDistance = c.getDouble("checks.scaffold.max-place-distance", 6.5);
        towerMinBlockIntervalMs = c.getLong("checks.scaffold.tower-min-interval-ms", 205L);

        fastBreakMinTimeRatio = c.getDouble("checks.fastbreak.min-time-ratio", 0.52);
        nukerMaxBlocksPerSec = c.getInt("checks.fastbreak.nuker-max-blocks-per-sec", 9);
        bedFuckerThroughWalls = c.getBoolean("checks.fastbreak.check-encased-beds", true);

        chestStealerMinAvgMs = c.getDouble("checks.inventory.stealer-min-avg-ms", 32.0);
        invMoveMinTicks = c.getInt("checks.inventory.invmove-min-ticks", 12);

        checkSettingsMap.clear();
        ConfigurationSection checksSec = c.getConfigurationSection("checks");
        for (CheckMeta meta : ALL_CHECKS) {
            ConfigurationSection s = checksSec != null ? checksSec.getConfigurationSection(meta.key()) : null;
            if (s != null) {
                boolean cancel = s.getBoolean("cancel-action",
                    s.getBoolean("cancel-hit", s.getBoolean("cancel-place", !meta.movementStyle())));
                checkSettingsMap.put(
                    meta.key(),
                    new CheckSettings(
                        s.getBoolean("enabled", true),
                        s.getDouble("buffer", 3.0),
                        s.getDouble("decay", 0.35),
                        s.getBoolean("setback", meta.movementStyle()),
                        cancel,
                        s.getDouble("max-vl", 16.0),
                        s.getStringList("punish-commands")
                    )
                );
            } else {
                checkSettingsMap.put(
                    meta.key(),
                    new CheckSettings(true, 3.0, 0.35, meta.movementStyle(), !meta.movementStyle(), 16.0, Collections.emptyList())
                );
            }
        }
    }

    public void setHologramHeightOffset(PurpurBedrockACPlugin plugin, double newHeight) {
        double clamped = Math.clamp(newHeight, 1.8, 5.0);
        plugin.getConfig().set("hologram.height-offset", clamped);
        plugin.saveConfig();
        this.hologramHeightOffset = clamped;
    }

    public CheckSettings check(String key) {
        CheckSettings found = checkSettingsMap.get(key.toLowerCase(Locale.ROOT));
        if (found != null) {
            return found;
        }
        return new CheckSettings(true, 3.0, 0.35, false, false, 16.0, Collections.emptyList());
    }

    public int countEnabledChecks() {
        int active = 0;
        for (CheckSettings s : checkSettingsMap.values()) {
            if (s.enabled) {
                active++;
            }
        }
        return active;
    }

    public CheckMeta findMeta(String key) {
        String norm = key.toLowerCase(Locale.ROOT);
        for (CheckMeta m : ALL_CHECKS) {
            if (m.key().equals(norm)) {
                return m;
            }
        }
        return null;
    }

    public boolean toggleCheckEnabled(PurpurBedrockACPlugin plugin, String key) {
        CheckMeta meta = findMeta(key);
        if (meta == null) {
            return false;
        }
        CheckSettings cur = check(meta.key());
        boolean next = !cur.enabled;
        plugin.getConfig().set("checks." + meta.key() + ".enabled", next);
        plugin.saveConfig();
        reload(plugin);
        return next;
    }

    public boolean toggleCheckMitigation(PurpurBedrockACPlugin plugin, String key) {
        CheckMeta meta = findMeta(key);
        if (meta == null) {
            return false;
        }
        CheckSettings cur = check(meta.key());
        boolean next;
        if (meta.movementStyle()) {
            next = !cur.setback;
            plugin.getConfig().set("checks." + meta.key() + ".setback", next);
        } else {
            next = !cur.cancelAction;
            plugin.getConfig().set("checks." + meta.key() + ".cancel-action", next);
        }
        plugin.saveConfig();
        reload(plugin);
        return next;
    }

    public boolean toggleAutoPunish(PurpurBedrockACPlugin plugin) {
        boolean next = !this.autoPunishEnabled;
        plugin.getConfig().set("protection.auto-punish-enabled", next);
        plugin.saveConfig();
        reload(plugin);
        return next;
    }

    private static Material parseMat(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material mat = Material.matchMaterial(raw.trim());
        return mat != null ? mat : fallback;
    }
}
