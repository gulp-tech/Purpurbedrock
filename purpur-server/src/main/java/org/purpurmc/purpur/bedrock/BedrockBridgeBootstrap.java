package org.purpurmc.purpur.bedrock;

import net.minecraft.server.dedicated.DedicatedServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.purpurmc.purpur.PurpurConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Self-contained Bedrock 26.2 protocol engine bootstrapper.
 * Manages root bedrock.yml, internal protocol runtime (without creating plugins/Geyser-Spigot),
 * NetherNet + RakNet + LAN Discovery (UDP 7551 & 19132), Floodgate auto-link,
 * and Windows 11 UWP LoopbackExempt helper generation.
 */
public final class BedrockBridgeBootstrap {
    public static final String EMBEDDED_RESOURCE = "/META-INF/bedrock/purpurbedrock-protocol.jar";
    private static volatile boolean protocolActive = false;
    private static volatile int activeBedrockPort = 19132;
    private static volatile int activeWebrtcPort = 19133;
    private static volatile int activeLanDiscoveryPort = 7551;
    private static volatile String activeTransport = "both";
    private static volatile String activeAuthMode = "offline";
    private static volatile String activeDataFolder = "bedrock";
    private static volatile boolean windowsLoopbackWarning = false;

    private BedrockBridgeBootstrap() {
    }

    public static boolean isProtocolActive() {
        return protocolActive || Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null;
    }

    public static int getActiveBedrockPort() {
        return activeBedrockPort;
    }

    public static int getActiveWebrtcPort() {
        return activeWebrtcPort;
    }

    public static int getActiveLanDiscoveryPort() {
        return activeLanDiscoveryPort;
    }

    public static String getActiveTransport() {
        return activeTransport;
    }

    public static String getActiveAuthMode() {
        return activeAuthMode;
    }

    public static String getActiveDataFolder() {
        return activeDataFolder;
    }

    public static boolean hasWindowsLoopbackWarning() {
        return windowsLoopbackWarning;
    }

    public static boolean isCustomSkullsEnabled() {
        return Boolean.parseBoolean(System.getProperty("purpurbedrock.customSkullsEnabled", "true"));
    }

    public static boolean isPromptOptionalPacksEnabled() {
        return Boolean.parseBoolean(System.getProperty("purpurbedrock.promptOptionalPacks", "true"));
    }

    public static void syncRuntimePackSettingsFromPurpurConfig() {
        boolean bedrockCfgSkulls = true;
        boolean bedrockCfgPrompt = true;
        File bedrockConfigFile = new File("bedrock.yml");
        if (bedrockConfigFile.isFile()) {
            YamlConfiguration bedrockCfg = YamlConfiguration.loadConfiguration(bedrockConfigFile);
            bedrockCfgSkulls = bedrockCfg.getBoolean("gameplay.custom-skulls-enabled", true);
            bedrockCfgPrompt = bedrockCfg.getBoolean("packs.prompt-optional-packs", true);
        }
        boolean resolvedSkulls = PurpurConfig.bedrockCustomSkullsEnabled && bedrockCfgSkulls;
        boolean resolvedPrompt = PurpurConfig.bedrockPromptOptionalPacks && bedrockCfgPrompt && resolvedSkulls;
        System.setProperty("purpurbedrock.customSkullsEnabled", Boolean.toString(resolvedSkulls));
        System.setProperty("purpurbedrock.promptOptionalPacks", Boolean.toString(resolvedPrompt));
        System.setProperty("purpurbedrock.safeRemotePlayerSkins", Boolean.toString(PurpurConfig.bedrockSafeRemotePlayerSkins));
        if (!resolvedSkulls) {
            clearOnlineSkullCaches();
        }
    }

    /**
     * Toggles custom skulls and optional pack download prompts live at runtime and persists to bedrock.yml and purpur.yml
     * without deleting cached player_skulls/*.png textures or removing custom-skull support from the core.
     */
    public static int setCustomSkullsEnabled(boolean enabled) {
        PurpurConfig.bedrockCustomSkullsEnabled = enabled;
        PurpurConfig.bedrockPromptOptionalPacks = enabled;
        System.setProperty("purpurbedrock.customSkullsEnabled", Boolean.toString(enabled));
        System.setProperty("purpurbedrock.promptOptionalPacks", Boolean.toString(enabled));

        try {
            File bedrockConfigFile = new File("bedrock.yml");
            if (bedrockConfigFile.isFile()) {
                YamlConfiguration bedrockCfg = YamlConfiguration.loadConfiguration(bedrockConfigFile);
                bedrockCfg.set("gameplay.custom-skulls-enabled", enabled);
                bedrockCfg.set("packs.prompt-optional-packs", enabled);
                bedrockCfg.save(bedrockConfigFile);
            }
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "[Purpurbedrock] Не удалось сохранить bedrock.yml", ex);
        }

        try {
            net.minecraft.server.MinecraftServer console = net.minecraft.server.MinecraftServer.getServer();
            if (console != null && PurpurConfig.config != null) {
                File purpurFile = (File) console.options.valueOf("purpur-settings");
                PurpurConfig.config.set("bedrock.custom-skulls.enabled", enabled);
                PurpurConfig.config.set("bedrock.custom-skulls.prompt-optional-packs", enabled);
                if (purpurFile != null) {
                    PurpurConfig.config.save(purpurFile);
                }
            }
        } catch (Throwable ex) {
            Bukkit.getLogger().log(Level.WARNING, "[Purpurbedrock] Не удалось сохранить purpur.yml", ex);
        }

        return enabled ? 0 : clearOnlineSkullCaches();
    }

    private static int clearOnlineSkullCaches() {
        int cleared = 0;
        try {
            Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
            if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
                return 0;
            }
            ClassLoader cl = geyserPlugin.getClass().getClassLoader();
            Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl", true, cl);
            Object geyser = geyserImplClass.getMethod("getInstance").invoke(null);
            if (geyser == null) {
                return 0;
            }
            Object sessionMgr = geyserImplClass.getMethod("getSessionManager").invoke(geyser);
            java.util.Collection<?> sessions = (java.util.Collection<?>) sessionMgr.getClass().getMethod("getAllSessions").invoke(sessionMgr);
            Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, cl);
            java.lang.reflect.Method ensureLoop = sessionClass.getMethod("ensureInEventLoop", Runnable.class);
            java.lang.reflect.Method getSkullCache = sessionClass.getMethod("getSkullCache");
            for (Object session : sessions) {
                if (session == null) {
                    continue;
                }
                Runnable clearTask = () -> {
                    try {
                        Object skullCache = getSkullCache.invoke(session);
                        if (skullCache != null) {
                            skullCache.getClass().getMethod("clear").invoke(skullCache);
                        }
                    } catch (Throwable ignored) {
                    }
                };
                ensureLoop.invoke(session, clearTask);
                cleared++;
            }
        } catch (Throwable ignored) {
        }
        return cleared;
    }

    public static void initialize(DedicatedServer server) {
        BedrockChunkHelper.applyRuntimeChunkTuning();
        if (!PurpurConfig.bedrockEmbeddedProtocolEnabled) {
            Bukkit.getLogger().info("[Purpurbedrock] Встроенный протокол Bedrock отключён в purpur.yml.");
            return;
        }

        try {
            File bedrockConfigFile = new File("bedrock.yml");
            YamlConfiguration bedrockCfg = loadOrCreateBedrockConfig(bedrockConfigFile, server);

            if (!bedrockCfg.getBoolean("enabled", true)) {
                Bukkit.getLogger().info("[Purpurbedrock] Протокол Bedrock отключён в bedrock.yml.");
                return;
            }

            checkWindowsLanLoopback();

            File pluginsDir = new File("plugins");
            boolean externalGeyserJarPresent = hasStandaloneGeyserSpigotJar(pluginsDir);
            boolean floodgateReady = detectFloodgateReady(pluginsDir);

            String configuredDataFolder = bedrockCfg.getString("data-folder", "bedrock");
            if (configuredDataFolder == null || configuredDataFolder.isBlank()) {
                configuredDataFolder = "bedrock";
            }
            activeDataFolder = configuredDataFolder;
            Path dataDir = Path.of(configuredDataFolder);
            prepareBedrockDataDirectories(dataDir, pluginsDir, bedrockCfg.getBoolean("auto-import-legacy-geyser", true));
            System.setProperty("purpurbedrock.dataDir", dataDir.toAbsolutePath().toString());
            System.setProperty("purpurbedrock.preserveSkullCache", "true");

            Path engineDir = Path.of("cache", "purpurbedrock", "engine");
            Files.createDirectories(engineDir);
            System.setProperty("purpurbedrock.engineDir", engineDir.toAbsolutePath().toString());
            System.setProperty("purpurbedrock.offlineAssets",
                bedrockCfg.getBoolean("advanced.download-mojang-assets", false) ? "false" : "true");

            boolean lanEnabled = bedrockCfg.getBoolean("lan-discovery.enabled", true);
            int lanPort = bedrockCfg.getInt("lan-discovery.port", 7551);
            int webrtcPort = bedrockCfg.getInt("nethernet.webrtc-port", 19133);
            int lanIcePort = bedrockCfg.getInt("lan-discovery.ice-port", webrtcPort + 1);
            activeLanDiscoveryPort = lanEnabled ? lanPort : 0;

            System.setProperty("purpurbedrock.lanDiscovery", Boolean.toString(lanEnabled));
            System.setProperty("purpurbedrock.lanDiscoveryPort", Integer.toString(lanPort));
            System.setProperty("purpurbedrock.lanIcePort", Integer.toString(lanIcePort));

            // Prevent RakServerRateLimiter (default 120 datagrams/10ms) from kicking legitimate 26.60 PC/CGNAT players
            if (System.getProperty("Geyser.RakPacketLimit") == null) {
                System.setProperty("Geyser.RakPacketLimit", Integer.toString(PurpurConfig.bedrockRakPacketLimit));
            }
            if (System.getProperty("Geyser.RakGlobalPacketLimit") == null) {
                System.setProperty("Geyser.RakGlobalPacketLimit", Integer.toString(PurpurConfig.bedrockRakGlobalPacketLimit));
            }
            if (System.getProperty("Geyser.MaxConnectionsPerAddress") == null) {
                System.setProperty("Geyser.MaxConnectionsPerAddress", Integer.toString(PurpurConfig.bedrockRakMaxConnectionsPerIp));
            }
            if (System.getProperty("Geyser.RakSendCookie") == null) {
                System.setProperty("Geyser.RakSendCookie", Boolean.toString(PurpurConfig.bedrockRakSendCookie));
            }
            if (System.getProperty("purpurbedrock.asyncPrewarm") == null) {
                System.setProperty("purpurbedrock.asyncPrewarm", Boolean.toString(PurpurConfig.bedrockAsyncGeyserPrewarm));
            }
            if (System.getProperty("purpurbedrock.eagerAllPalettes") == null) {
                System.setProperty("purpurbedrock.eagerAllPalettes", Boolean.toString(!PurpurConfig.bedrockLazyLegacyPalettes));
            }
            if (System.getProperty("purpurbedrock.maxParticlesPerPacket") == null) {
                System.setProperty("purpurbedrock.maxParticlesPerPacket", Integer.toString(PurpurConfig.bedrockMaxParticlesPerPacket));
            }
            if (System.getProperty("purpurbedrock.safeRemotePlayerSkins") == null) {
                System.setProperty("purpurbedrock.safeRemotePlayerSkins", Boolean.toString(PurpurConfig.bedrockSafeRemotePlayerSkins));
            }
            if (System.getProperty("purpurbedrock.maxCachedCustomSkulls") == null) {
                int maxCachedSkulls = Math.max(0, bedrockCfg.getInt("performance.max-cached-custom-skulls", 16));
                System.setProperty("purpurbedrock.maxCachedCustomSkulls", Integer.toString(maxCachedSkulls));
            }
            if (System.getProperty("purpurbedrock.allowVibrantVisuals") == null) {
                System.setProperty(
                    "purpurbedrock.allowVibrantVisuals",
                    Boolean.toString(bedrockCfg.getBoolean("gameplay.allow-vibrant-visuals", false))
                );
            }
            if (System.getProperty("purpurbedrock.maxMobileRenderDistance") == null) {
                System.setProperty(
                    "purpurbedrock.maxMobileRenderDistance",
                    Integer.toString(PurpurConfig.bedrockMaxMobileRenderDistance)
                );
            }
            if (System.getProperty("purpurbedrock.maxClientCommands") == null) {
                System.setProperty(
                    "purpurbedrock.maxClientCommands",
                    Integer.toString(PurpurConfig.bedrockMaxClientCommands)
                );
            }
            boolean resolvedCustomSkulls = PurpurConfig.bedrockCustomSkullsEnabled
                && bedrockCfg.getBoolean("gameplay.custom-skulls-enabled", true);
            boolean resolvedPromptOptionalPacks = PurpurConfig.bedrockPromptOptionalPacks
                && bedrockCfg.getBoolean("packs.prompt-optional-packs", true)
                && resolvedCustomSkulls;
            if (System.getProperty("purpurbedrock.customSkullsEnabled") == null) {
                System.setProperty("purpurbedrock.customSkullsEnabled", Boolean.toString(resolvedCustomSkulls));
            }
            if (System.getProperty("purpurbedrock.promptOptionalPacks") == null) {
                System.setProperty("purpurbedrock.promptOptionalPacks", Boolean.toString(resolvedPromptOptionalPacks));
            }

            syncInternalEngineConfig(bedrockCfg, server, engineDir, floodgateReady);

            if (externalGeyserJarPresent) {
                Bukkit.getLogger().info("[Purpurbedrock] Обнаружен внешний Bedrock-модуль в plugins/ — встроенный серверный модуль пропущен.");
                protocolActive = true;
                return;
            }

            try (InputStream in = BedrockBridgeBootstrap.class.getResourceAsStream(EMBEDDED_RESOURCE)) {
                if (in == null) {
                    Bukkit.getLogger().info("[Purpurbedrock] Встроенный модуль протокола не найден в ресурсах JAR (dev-сборка).");
                    return;
                }

                Path cacheDir = Path.of("cache", "purpurbedrock");
                Files.createDirectories(cacheDir);
                Path extractedJar = cacheDir.resolve("purpurbedrock-protocol-26.2.jar");
                Files.copy(in, extractedJar, StandardCopyOption.REPLACE_EXISTING);

                Plugin loaded = Bukkit.getPluginManager().loadPlugin(extractedJar.toFile());
                if (loaded != null) {
                    protocolActive = true;
                    Bukkit.getLogger().info(String.format(
                        "[Purpurbedrock] Встроенный протокол Bedrock 26.2 активен! Мульти-версия: 1.21.0–26.60 (v685–v2225, 24 кодека) | RakNet/Signaling: %s:%d | NetherNet WebRTC: UDP %d | LAN Discovery (Миры): %s | Auth: %s",
                        bedrockCfg.getString("address", "0.0.0.0"),
                        activeBedrockPort,
                        activeWebrtcPort,
                        lanEnabled ? ("UDP " + lanPort + " + UDP 19132") : "выкл",
                        activeAuthMode
                    ));
                }
            }
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.SEVERE, "[Purpurbedrock] Ошибка инициализации встроенного протокола Bedrock", t);
        }
    }

    /**
     * Ensures native C++ libdatachannel / NetherNet threads never hang the JVM process
     * after DedicatedServer.stopServer() has saved all worlds and disabled all plugins.
     */
    public static void onServerStopped() {
        if (!protocolActive) {
            return;
        }
        Thread exitFinisher = new Thread(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "Purpurbedrock-Shutdown-Finisher");
        exitFinisher.setDaemon(true);
        exitFinisher.start();
    }

    private static boolean hasStandaloneGeyserSpigotJar(File pluginsDir) {
        if (!pluginsDir.isDirectory()) {
            return false;
        }
        File[] jars = pluginsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) {
            return false;
        }
        for (File jarFile : jars) {
            try (JarFile jf = new JarFile(jarFile)) {
                JarEntry pluginYml = jf.getJarEntry("plugin.yml");
                if (pluginYml == null) {
                    pluginYml = jf.getJarEntry("paper-plugin.yml");
                }
                if (pluginYml != null) {
                    try (InputStream in = jf.getInputStream(pluginYml)) {
                        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        for (String rawLine : content.split("\\R")) {
                            String line = rawLine.trim();
                            if (line.startsWith("name:")) {
                                String declaredName = line.substring("name:".length()).trim().replace("\"", "").replace("'", "");
                                if (declaredName.equalsIgnoreCase("Geyser-Spigot")) {
                                    return true;
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean detectFloodgateReady(File pluginsDir) {
        if (!pluginsDir.isDirectory()) {
            return false;
        }
        File keyPem = new File(new File(pluginsDir, "floodgate"), "key.pem");
        File[] files = pluginsDir.listFiles();
        boolean hasFloodgateJar = false;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).contains("floodgate") && f.getName().endsWith(".jar")) {
                    hasFloodgateJar = true;
                    break;
                }
            }
        }
        return hasFloodgateJar && keyPem.isFile();
    }

    private static void checkWindowsLanLoopback() {
        // Generate unlock-windows-lan.bat (helpful on native Windows 10/11 and WSL2)
        Path helperBat = Path.of("unlock-windows-lan.bat");
        String batContent = String.join("\r\n",
            "@echo off",
            "chcp 65001 >nul",
            "net session >nul 2>&1",
            "if %errorLevel% neq 0 (",
            "    echo Запрос прав Администратора для разблокировки локальной сети Minecraft Windows 10/11...",
            "    powershell -NoProfile -Command \"Start-Process -FilePath '%~f0' -Verb RunAs\"",
            "    exit /b",
            ")",
            "echo [Purpurbedrock 26.2] Разблокировка локальной сети для всех установленных пакетов Minecraft...",
            "CheckNetIsolation LoopbackExempt -a -n=\"Microsoft.MinecraftUWP_8wekyb3d8bbwe\" >nul 2>&1",
            "CheckNetIsolation LoopbackExempt -a -n=\"Microsoft.MinecraftWindowsBeta_8wekyb3d8bbwe\" >nul 2>&1",
            "for /f \"usebackq delims=\" %%P in (`powershell -NoProfile -Command \"Get-AppxPackage '*Minecraft*' ^| ForEach-Object { $_.PackageFamilyName }\"`) do CheckNetIsolation LoopbackExempt -a -n=\"%%P\" >nul 2>&1",
            "echo [Purpurbedrock 26.2] Обновление правил Брандмауэра Windows для LAN Discovery...",
            "netsh advfirewall firewall delete rule name=\"Purpurbedrock LAN UDP\" >nul 2>&1",
            "netsh advfirewall firewall delete rule name=\"Purpurbedrock LAN TCP\" >nul 2>&1",
            "netsh advfirewall firewall add rule name=\"Purpurbedrock LAN UDP\" dir=in action=allow protocol=UDP localport=7551,19132-19134 profile=any >nul 2>&1",
            "netsh advfirewall firewall add rule name=\"Purpurbedrock LAN UDP\" dir=out action=allow protocol=UDP localport=7551,19132-19134 profile=any >nul 2>&1",
            "netsh advfirewall firewall add rule name=\"Purpurbedrock LAN TCP\" dir=in action=allow protocol=TCP localport=19132 profile=any >nul 2>&1",
            "netsh advfirewall firewall add rule name=\"Purpurbedrock LAN TCP\" dir=out action=allow protocol=TCP localport=19132 profile=any >nul 2>&1",
            "echo.",
            "echo ГОТОВО. Полностью закройте Minecraft, запустите сервер, затем снова откройте Minecraft.",
            "echo Сервер появится в разделе Миры только если сервер и компьютер находятся в одной локальной сети.",
            "pause"
        ) + "\r\n";
        try {
            // Always refresh the helper so servers upgraded from an older core receive current rules.
            Files.writeString(helperBat, batContent, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }

        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        try {
            Process proc = new ProcessBuilder("CheckNetIsolation", "LoopbackExempt", "-s")
                .redirectErrorStream(true)
                .start();
            boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
            if (finished) {
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (!out.contains("minecraftuwp")) {
                    windowsLoopbackWarning = true;
                    Bukkit.getLogger().warning("=========================================================================");
                    Bukkit.getLogger().warning("[Purpurbedrock] ВНИМАНИЕ (Windows 10/11): Изоляция UWP блокирует LAN на одном ПК!");
                    Bukkit.getLogger().warning("[Purpurbedrock] Чтобы видеть сервер во вкладке 'Миры' с ЭТОГО ЖЕ ПК,");
                    Bukkit.getLogger().warning("[Purpurbedrock] запустите один раз созданный файл: unlock-windows-lan.bat");
                    Bukkit.getLogger().warning("=========================================================================");
                }
            } else {
                proc.destroyForcibly();
            }
        } catch (Throwable ignored) {
        }
    }

    private static YamlConfiguration loadOrCreateBedrockConfig(File file, DedicatedServer server) throws IOException {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.options().setHeader(java.util.List.of(
            "Purpurbedrock 26.2 — Конфигурация встроенного ядра Minecraft: Bedrock Edition",
            "Внешние мосты не требуются: протокол Bedrock, LAN Discovery (UDP 7551 + 19132),",
            "WebRTC и поддержка всех Java-плагинов и меню встроены прямо в сервер."
        ));
        cfg.addDefault("enabled", true);
        cfg.addDefault("data-folder", "bedrock");
        cfg.addDefault("auto-import-legacy-geyser", true);
        cfg.addDefault("address", "0.0.0.0");
        cfg.addDefault("port", 19132);
        cfg.addDefault("transport", "both");
        cfg.addDefault("nethernet.webrtc-port", 19133);
        cfg.addDefault("nethernet.public-address", "");
        cfg.addDefault("lan-discovery.enabled", true);
        cfg.addDefault("lan-discovery.port", 7551);
        cfg.addDefault("lan-discovery.ice-port", 19134);
        cfg.addDefault("auth-type", "auto");
        cfg.addDefault("xbox-auth-required", false);
        cfg.addDefault("motd.line-1", "Purpurbedrock 26.2");
        cfg.addDefault("motd.line-2", "Bedrock Only • Java 26.2 Plugins & Menus");
        cfg.addDefault("max-players", Math.max(20, server.getMaxPlayers()));
        cfg.addDefault("gameplay.command-suggestions", true);
        cfg.addDefault("gameplay.show-cooldown", "false");
        cfg.addDefault("gameplay.disable-bedrock-scaffolding", false);
        cfg.addDefault("gameplay.allow-vibrant-visuals", false);
        cfg.addDefault("gameplay.custom-skulls-enabled", true);
        cfg.addDefault("performance.mtu", 1200);
        cfg.addDefault("performance.compression-level", 4);
        cfg.addDefault("performance.cache-images", 7);
        cfg.addDefault("performance.max-visible-custom-skulls", 12);
        cfg.addDefault("performance.custom-skull-render-distance", 12);
        cfg.addDefault("performance.max-cached-custom-skulls", 16);
        cfg.addDefault("packs.force-resource-packs", false);
        cfg.addDefault("packs.enable-integrated-pack", true);
        cfg.addDefault("packs.prompt-optional-packs", true);
        cfg.addDefault("advanced.download-mojang-assets", false);
        cfg.addDefault("config-schema", 6);
        int existingSchema = cfg.getInt("config-schema", 1);
        if (file.exists() && existingSchema < 2) {
            cfg.set("packs.enable-integrated-pack", true);
        }
        if (file.exists() && existingSchema < 4) {
            // Migrate MTU 1350 -> 1200 for Russian mobile LTE / PPPoE / VPN / Amnezia compatibility,
            // and make resource packs non-blocking while preserving GeyserIntegratedPack + HeadDatabase skulls
            if (cfg.getInt("performance.mtu", 1350) == 1350) {
                cfg.set("performance.mtu", 1200);
            }
            if (cfg.getBoolean("packs.force-resource-packs", true)) {
                cfg.set("packs.force-resource-packs", false);
            }
        }
        if (file.exists() && existingSchema < 5) {
            // Migrate max-cached-custom-skulls 2048 -> 16 so 1,459+ HeadDatabase PNGs never generate
            // 30,639+ CustomSkull VoxelShapes/Molang permutations that crash 3-4GB RAM phones (e.g. Galaxy A07)
            int curStaticSkulls = cfg.getInt("performance.max-cached-custom-skulls", 2048);
            if (curStaticSkulls == 0 || curStaticSkulls > 32) {
                cfg.set("performance.max-cached-custom-skulls", 16);
            }
            int curVisSkulls = cfg.getInt("performance.max-visible-custom-skulls", 24);
            if (curVisSkulls == 0 || curVisSkulls >= 24) {
                cfg.set("performance.max-visible-custom-skulls", 12);
            }
            int curSkullDist = cfg.getInt("performance.custom-skull-render-distance", 16);
            if (curSkullDist == 0 || curSkullDist >= 16) {
                cfg.set("performance.custom-skull-render-distance", 12);
            }
            cfg.set("gameplay.allow-vibrant-visuals", false);
            cfg.set("config-schema", 5);
        }
        if (file.exists() && existingSchema < 6) {
            cfg.set("config-schema", 6);
        }
        cfg.options().copyDefaults(true);
        cfg.save(file);
        return cfg;
    }

    private static void prepareBedrockDataDirectories(Path dataDir, File pluginsDir, boolean autoImportLegacy) throws IOException {
        String[] subdirs = {
            "cache",
            "cache/player_skulls",
            "cache/images",
            "cache/default_player_skins",
            "cache/remote_packs",
            "custom_mappings",
            "extensions",
            "packs"
        };
        for (String sub : subdirs) {
            Files.createDirectories(dataDir.resolve(sub));
        }

        int importedFiles = 0;
        if (autoImportLegacy && pluginsDir.isDirectory()) {
            Path legacyGeyserDir = pluginsDir.toPath().resolve("Geyser-Spigot");
            if (Files.isDirectory(legacyGeyserDir)) {
                String[] topSubdirs = {"cache", "custom_mappings", "extensions", "packs"};
                for (String sub : topSubdirs) {
                    Path srcDir = legacyGeyserDir.resolve(sub);
                    Path dstDir = dataDir.resolve(sub);
                    if (Files.isDirectory(srcDir)) {
                        importedFiles += copyMissingRecursively(srcDir, dstDir);
                    }
                }
                Path legacyCustomSkulls = legacyGeyserDir.resolve("custom-skulls.yml");
                Path dstCustomSkulls = dataDir.resolve("custom-skulls.yml");
                if (Files.isRegularFile(legacyCustomSkulls) && !Files.exists(dstCustomSkulls)) {
                    Files.copy(legacyCustomSkulls, dstCustomSkulls);
                    importedFiles++;
                }
            }
        }

        Bukkit.getLogger().info(String.format(
            "[Purpurbedrock] Рабочие папки готовы: %s/cache (player_skulls / HeadDatabase) | %s/custom_mappings | %s/extensions | %s/packs",
            dataDir, dataDir, dataDir, dataDir
        ));
        if (importedFiles > 0) {
            Bukkit.getLogger().info(String.format(
                "[Purpurbedrock] Авто-импортировано %d файл(ов) из старой папки Bedrock-модуля в %s/!",
                importedFiles, dataDir
            ));
        }
    }

    private static int copyMissingRecursively(Path sourceRoot, Path targetRoot) {
        int copied = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path src : walk.toList()) {
                Path rel = sourceRoot.relativize(src);
                Path dst = targetRoot.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (Files.isRegularFile(src) && !Files.exists(dst)) {
                    if (dst.getParent() != null) {
                        Files.createDirectories(dst.getParent());
                    }
                    Files.copy(src, dst);
                    copied++;
                }
            }
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "[Purpurbedrock] Не удалось скопировать часть файлов из " + sourceRoot, ex);
        }
        return copied;
    }

    private static void syncInternalEngineConfig(
        YamlConfiguration bedrockCfg,
        DedicatedServer server,
        Path engineDir,
        boolean floodgateReady
    ) throws IOException {
        int bedrockPort = bedrockCfg.getInt("port", 19132);
        int webrtcPort = bedrockCfg.getInt("nethernet.webrtc-port", 19133);
        if (webrtcPort == bedrockPort) {
            webrtcPort = bedrockPort + 1;
        }
        activeBedrockPort = bedrockPort;
        activeWebrtcPort = webrtcPort;

        String bindAddress = bedrockCfg.getString("address", "0.0.0.0");
        String transport = bedrockCfg.getString("transport", "both");
        activeTransport = transport;

        int javaPort = server.getPort() > 0 ? server.getPort() : 25565;
        String configuredAuth = bedrockCfg.getString("auth-type", "auto");
        String resolvedAuth;
        if ("auto".equalsIgnoreCase(configuredAuth)) {
            resolvedAuth = floodgateReady ? "floodgate" : (server.usesAuthentication() ? "online" : "offline");
        } else if ("floodgate".equalsIgnoreCase(configuredAuth) && !floodgateReady) {
            resolvedAuth = "offline";
        } else {
            resolvedAuth = configuredAuth;
        }
        activeAuthMode = resolvedAuth;

        boolean validateBedrockLogin = bedrockCfg.getBoolean("xbox-auth-required", false);
        String motd1 = escapeYaml(bedrockCfg.getString("motd.line-1", "Purpurbedrock 26.2"));
        String motd2 = escapeYaml(bedrockCfg.getString("motd.line-2", "Bedrock Only • Java 26.2 Plugins & Menus"));
        int maxPlayers = bedrockCfg.getInt("max-players", Math.max(20, server.getMaxPlayers()));
        boolean commandSuggestions = bedrockCfg.getBoolean("gameplay.command-suggestions", true);
        String rawCooldown = bedrockCfg.getString("gameplay.show-cooldown", "disabled");
        String cooldownType = ("false".equalsIgnoreCase(rawCooldown) || "disabled".equalsIgnoreCase(rawCooldown))
            ? "disabled"
            : ("hotbar".equalsIgnoreCase(rawCooldown) ? "hotbar" : "crosshair");
        boolean disableScaffolding = bedrockCfg.getBoolean("gameplay.disable-bedrock-scaffolding", false);
        int mtu = bedrockCfg.getInt("performance.mtu", 1200);
        int compressionLevel = bedrockCfg.getInt("performance.compression-level", 4);
        int cacheImages = bedrockCfg.getInt("performance.cache-images", 7);
        boolean customSkullsEnabled = PurpurConfig.bedrockCustomSkullsEnabled
            && bedrockCfg.getBoolean("gameplay.custom-skulls-enabled", true);
        int maxSkulls = customSkullsEnabled
            ? bedrockCfg.getInt("performance.max-visible-custom-skulls", 12)
            : 0;
        int skullRenderDist = Math.max(8, Math.min(64, bedrockCfg.getInt("performance.custom-skull-render-distance", 12)));
        boolean forcePacks = bedrockCfg.getBoolean("packs.force-resource-packs", false);
        boolean integratedPack = bedrockCfg.getBoolean("packs.enable-integrated-pack", true);

        String rawPublicAddr = bedrockCfg.getString("nethernet.public-address", "");
        String advertiseYaml = resolveAdvertiseAddressesYaml(rawPublicAddr);

        // Internal engine config-version 10 (verified 1:1 against GeyserConfig 26.2 interface)
        String internalYaml = """
            # Auto-generated by Purpurbedrock 26.2 from root bedrock.yml. Edit bedrock.yml instead!
            bedrock:
              address: "%s"
              port: %d
              webrtc-port: %d
              transport: %s
              clone-remote-port: false
              signaling:
                mode: builtin
                builtin:
                  advertise-addresses: %s
                  https:
                    certificate: ""
                    private-key: ""
                    password: ""
                nxs:
                  endpoint: "https://agent.warden.cloud"
                  token: ""
                  advertise-addresses: []
            java:
              address: "127.0.0.1"
              port: %d
              auth-type: "%s"
              forward-hostname: false
            motd:
              primary-motd: "%s"
              secondary-motd: "%s"
              passthrough-motd: true
              passthrough-player-counts: true
              integrated-ping-passthrough: true
              ping-passthrough-interval: 3
              max-players: %d
            gameplay:
              server-name: "%s"
              cooldown-type: "%s"
              command-suggestions: %s
              show-coordinates: true
              disable-bedrock-scaffolding: %s
              nether-roof-workaround: false
              emotes-enabled: true
              block-legacy-codes: true
              unusable-space-block: "minecraft:barrier"
              enable-custom-content: true
              force-resource-packs: %s
              enable-integrated-pack: %s
              forward-player-ping: true
              xbox-achievements-enabled: false
              max-visible-custom-skulls: %d
              custom-skull-render-distance: %d
            default-locale: "ru_ru"
            log-player-ip-addresses: true
            saved-user-logins: []
            pending-authentication-timeout: 120
            notify-on-new-bedrock-update: false
            advanced:
              cache-images: %d
              scoreboard-packet-threshold: 8
              add-team-suggestions: true
              resource-pack-urls: []
              floodgate-key-file: "key.pem"
              java:
                use-haproxy-protocol: false
                use-direct-connection: true
                disable-compression: true
              bedrock:
                broadcast-port: 0
                compression-level: %d
                use-haproxy-protocol: false
                haproxy-protocol-whitelisted-ips: []
                signaling-trusted-proxies: []
                use-waterdogpe-forwarding: false
                mtu: %d
                validate-bedrock-login: %s
              nxs:
                assisted-joins: false
                connection-checks: false
                stun-discovery: true
                location: {}
                deregister-on-shutdown: true
            debug-mode: false
            config-version: 10
            """.formatted(
            escapeYaml(bindAddress),
            bedrockPort,
            webrtcPort,
            transport,
            advertiseYaml,
            javaPort,
            escapeYaml(resolvedAuth),
            motd1,
            motd2,
            maxPlayers,
            motd1,
            cooldownType,
            commandSuggestions,
            disableScaffolding,
            forcePacks,
            integratedPack,
            maxSkulls,
            skullRenderDist,
            cacheImages,
            compressionLevel,
            mtu,
            validateBedrockLogin
        );

        Files.createDirectories(engineDir);
        Files.writeString(engineDir.resolve("config.yml"), internalYaml, StandardCharsets.UTF_8);
    }

    public static String runProtocolSelfTest() {
        int[] expectedProtocols = {
            685, 686, 712, 729, 748, 766, 776, 786, 800, 818, 819,
            827, 844, 859, 860, 898, 924, 944, 975, 1001, 2168, 2169, 2193, 2225
        };
        try {
            Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
            if (geyserPlugin == null) {
                throw new IllegalStateException("Internal Bedrock server module not loaded");
            }
            ClassLoader cl = geyserPlugin.getClass().getClassLoader();
            Class<?> gameProtocolClass = Class.forName("org.geysermc.geyser.network.bedrock.GameProtocol", true, cl);
            java.lang.reflect.Method getBedrockCodec = gameProtocolClass.getMethod("getBedrockCodec", int.class);
            java.util.List<?> protocols = (java.util.List<?>) gameProtocolClass.getField("SUPPORTED_BEDROCK_PROTOCOLS").get(null);
            if (protocols.size() < expectedProtocols.length) {
                throw new IllegalStateException("Expected >= 24 codecs, got " + protocols.size());
            }

            Class<?> blockRegistriesClass = Class.forName("org.geysermc.geyser.registry.BlockRegistries", true, cl);
            Object blocksReg = blockRegistriesClass.getField("BLOCKS").get(null);
            java.lang.reflect.Method blocksForVersion = blocksReg.getClass().getMethod("forVersion", int.class);

            Class<?> registriesClass = Class.forName("org.geysermc.geyser.registry.Registries", true, cl);
            Object itemsReg = registriesClass.getField("ITEMS").get(null);
            Object tagsReg = registriesClass.getField("TAGS").get(null);
            Object potionReg = registriesClass.getField("POTION_MIXES").get(null);
            java.lang.reflect.Method itemsForVersion = itemsReg.getClass().getMethod("forVersion", int.class);
            java.lang.reflect.Method tagsForVersion = tagsReg.getClass().getMethod("forVersion", int.class);
            java.lang.reflect.Method potionsForVersion = potionReg.getClass().getMethod("forVersion", int.class);

            for (int proto : expectedProtocols) {
                Object codec = getBedrockCodec.invoke(null, proto);
                if (codec == null) {
                    throw new IllegalStateException("Missing BedrockCodec for protocol v" + proto);
                }
                if (blocksForVersion.invoke(blocksReg, proto) == null) {
                    throw new IllegalStateException("Missing BlockMappings for protocol v" + proto);
                }
                Object itemMappings = itemsForVersion.invoke(itemsReg, proto);
                if (itemMappings == null) {
                    throw new IllegalStateException("Missing ItemMappings for protocol v" + proto);
                }
                Object[] mappedItems = (Object[]) itemMappings.getClass().getMethod("getItems").invoke(itemMappings);
                if (mappedItems == null || mappedItems.length < 1000) {
                    throw new IllegalStateException("Incomplete ItemMappings for protocol v" + proto);
                }
                if (tagsForVersion.invoke(tagsReg, proto) == null) {
                    throw new IllegalStateException("Missing ItemTags for protocol v" + proto);
                }
                if (potionsForVersion.invoke(potionReg, proto) == null) {
                    throw new IllegalStateException("Missing PotionMixes for protocol v" + proto);
                }
            }

            try (InputStream dhIn = cl.getResourceAsStream("org/geysermc/geyser/session/dialog/DialogHolder.class")) {
                if (dhIn == null) {
                    throw new IllegalStateException("DialogHolder.class not found");
                }
                String raw = new String(dhIn.readAllBytes(), StandardCharsets.ISO_8859_1);
                if (raw.contains("gui.waitingForResponse.title")) {
                    throw new IllegalStateException("DialogHolder still contains gui.waitingForResponse.title!");
                }
            }

            Class<?> chunkUtilsClass = Class.forName("org.geysermc.geyser.util.ChunkUtils", true, cl);
            Class<?> geyserSessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, cl);
            chunkUtilsClass.getMethod(
                "ensureChunkPublished",
                geyserSessionClass,
                int.class,
                int.class
            );
            chunkUtilsClass.getMethod(
                "forceUpdateChunkPositionBlock",
                geyserSessionClass,
                int.class,
                int.class,
                int.class
            );

            // Verify zero-regression username preservation + non-Xbox XUID isolation
            Class<?> loginEncClass = Class.forName("org.geysermc.geyser.util.LoginEncryptionUtils", true, cl);
            java.lang.reflect.Method sanitizeName = loginEncClass.getMethod("sanitizeBedrockUsername", String.class, String.class);
            java.lang.reflect.Method offlineXuid = loginEncClass.getMethod("computeDeterministicOfflineXuid", String.class);
            if (!"BaronLite2".equals(sanitizeName.invoke(null, "BaronLite2", "dev-1"))
                || !"Baron_Lite".equals(sanitizeName.invoke(null, "Baron Lite", "dev-2"))
                || !"eni02".equals(sanitizeName.invoke(null, "eni02", "dev-3"))) {
                throw new IllegalStateException("Existing valid usernames were altered by sanitizeBedrockUsername!");
            }
            String stivA = (String) sanitizeName.invoke(null, "Стив", "android-device-aaa");
            String stivB = (String) sanitizeName.invoke(null, "Стив", "android-device-bbb");
            String cyrCustom = (String) sanitizeName.invoke(null, "Ваня-Про.2026", "android-device-ccc");
            if (stivA.equals(stivB) || !stivA.matches("^[a-zA-Z0-9_]{3,16}$") || !cyrCustom.matches("^[a-zA-Z0-9_]{3,16}$")) {
                throw new IllegalStateException("Broken/Cyrillic username sanitization failed: " + stivA + " / " + stivB + " / " + cyrCustom);
            }
            String xuidA = (String) offlineXuid.invoke(null, stivA);
            String xuidB = (String) offlineXuid.invoke(null, stivB);
            String xuidA2 = (String) offlineXuid.invoke(null, stivA);
            if (xuidA == null || xuidA.isBlank() || "0".equals(xuidA) || xuidA.equals(xuidB) || !xuidA.equals(xuidA2)) {
                throw new IllegalStateException("Offline deterministic XUID check failed: " + xuidA + " vs " + xuidB);
            }

            Class<?> mcLocaleClass = Class.forName("org.geysermc.geyser.text.MinecraftLocale", true, cl);
            java.lang.reflect.Method getLocaleIfPresent = mcLocaleClass.getMethod("getLocaleStringIfPresent", String.class, String.class);
            String ruSpawn = (String) getLocaleIfPresent.invoke(null, "block.minecraft.spawn.not_valid", "ru_ru");
            String ruJoined = (String) getLocaleIfPresent.invoke(null, "multiplayer.player.joined", "ru_ru");
            String ruPotion = (String) getLocaleIfPresent.invoke(null, "potion.withDuration", "ru_ru");
            String ruSpeed = (String) getLocaleIfPresent.invoke(null, "effect.minecraft.speed", "ru_ru");
            String ruAdvChallenge = (String) getLocaleIfPresent.invoke(null, "chat.type.advancement.challenge", "ru_ru");
            String ruAdvGoal = (String) getLocaleIfPresent.invoke(null, "chat.type.advancement.goal", "ru_ru");
            if (ruSpawn == null || ruSpawn.contains("spawn.not_valid")
                || ruJoined == null || ruJoined.contains("multiplayer.player")
                || ruPotion == null || !ruPotion.contains("%s")
                || ruSpeed == null || ruSpeed.contains("effect.minecraft")
                || ruAdvChallenge == null || ruAdvChallenge.contains("chat.type.advancement")
                || ruAdvGoal == null || ruAdvGoal.contains("chat.type.advancement")) {
                throw new IllegalStateException(
                    "Russian system locales incomplete: potion=" + ruPotion + " speed=" + ruSpeed
                    + " adv=" + ruAdvChallenge + "/" + ruAdvGoal
                );
            }

            // Verify Windows GDK 26.60 DeviceOS=16 (e.g. ZET Gaming desktop) resolves to UWP ("Windows") instead of UNKNOWN
            Class<?> clientDataClass = Class.forName("org.geysermc.geyser.session.auth.BedrockClientData", true, cl);
            com.google.gson.Gson gson = new com.google.gson.Gson();
            Object gdkData = gson.fromJson(
                "{\"DeviceOS\":16,\"DeviceModel\":\"WARD H281 ZET Gaming\",\"CurrentInputMode\":1,\"DefaultInputMode\":1,\"UIProfile\":0}",
                clientDataClass
            );
            Object resolvedOs = clientDataClass.getMethod("getDeviceOs").invoke(gdkData);
            if (!(resolvedOs instanceof Enum<?> osEnum) || !"UWP".equals(osEnum.name())) {
                throw new IllegalStateException("Windows GDK DeviceOS=16 did not resolve to UWP: " + resolvedOs);
            }

            java.util.BitSet chunkBlockEntities = (java.util.BitSet) blockRegistriesClass.getField("BEDROCK_CHUNK_BLOCK_ENTITIES").get(null);
            if (chunkBlockEntities == null || chunkBlockEntities.isEmpty()) {
                throw new IllegalStateException("BEDROCK_CHUNK_BLOCK_ENTITIES BitSet was not populated!");
            }

            try (InputStream gsIn = cl.getResourceAsStream("org/geysermc/geyser/session/GeyserSession.class")) {
                if (gsIn == null || !new String(gsIn.readAllBytes(), StandardCharsets.ISO_8859_1).contains("setAuthoritativeMovementMode")) {
                    throw new IllegalStateException("Bedrock session missing setAuthoritativeMovementMode (<1.21.90 StartGamePacket fix)!");
                }
            }
            try (InputStream usIn = cl.getResourceAsStream("org/geysermc/geyser/session/UpstreamSession.class")) {
                if (usIn == null || !new String(usIn.readAllBytes(), StandardCharsets.ISO_8859_1).contains("setAttributes")) {
                    throw new IllegalStateException("UpstreamSession missing immutable-safe setAttributes (<26.30 SingletonList fix)!");
                }
            }
            try (InputStream smIn = cl.getResourceAsStream("org/geysermc/geyser/skin/SkinManager.class")) {
                if (smIn == null || !new String(smIn.readAllBytes(), StandardCharsets.ISO_8859_1).contains("purpurbedrock.safeRemotePlayerSkins")) {
                    throw new IllegalStateException("SkinManager missing mobile-safe remote player skin guard!");
                }
            }
            Class<?> azimuthClass = Class.forName("org.geysermc.geyser.session.cache.waypoint.AzimuthWaypoint", true, cl);
            azimuthClass.getDeclaredField("tickCounter");
            Class<?> particlesTransClass = Class.forName("org.geysermc.geyser.translator.protocol.java.level.JavaLevelParticlesTranslator", true, cl);
            java.lang.reflect.Field maxParticlesField = particlesTransClass.getDeclaredField("MAX_PARTICLES");
            maxParticlesField.setAccessible(true);
            int activeParticleCap = maxParticlesField.getInt(null);
            if (activeParticleCap <= 0 || activeParticleCap > 64) {
                throw new IllegalStateException("Unexpected MAX_PARTICLES cap: " + activeParticleCap);
            }
            Class<?> cmdTransClass = Class.forName("org.geysermc.geyser.translator.protocol.java.JavaCommandsTranslator", true, cl);
            cmdTransClass.getDeclaredField("PRIORITY_PLAYER_COMMANDS");
            Class<?> skullCacheClass = Class.forName("org.geysermc.geyser.session.cache.SkullCache", true, cl);
            skullCacheClass.getDeclaredField("DISTANCE_COMPARATOR");
            Class<?> formCacheClass = Class.forName("org.geysermc.geyser.session.cache.FormCache", true, cl);
            formCacheClass.getDeclaredField("userBusyRetries");
            geyserSessionClass.getMethod("prepareForConfigurationForm");
            int activeStaticSkullCap = Integer.getInteger("purpurbedrock.maxCachedCustomSkulls", 16);
            if (activeStaticSkullCap < 0 || activeStaticSkullCap > 64) {
                throw new IllegalStateException("Mobile-unsafe maxCachedCustomSkulls cap: " + activeStaticSkullCap);
            }
            try (InputStream lcIn = cl.getResourceAsStream("org/geysermc/geyser/translator/protocol/java/level/JavaLevelChunkWithLightTranslator.class")) {
                if (lcIn == null) {
                    throw new IllegalStateException("JavaLevelChunkWithLightTranslator.class not found");
                }
                String lcRaw = new String(lcIn.readAllBytes(), StandardCharsets.ISO_8859_1);
                if (!lcRaw.contains("purpurbedrock.customSkullsEnabled") || !lcRaw.contains("sendEmptyChunk")) {
                    throw new IllegalStateException("JavaLevelChunkWithLightTranslator missing bounds-safe chunk & skull toggle guards!");
                }
            }
            try (InputStream rpIn = cl.getResourceAsStream("org/geysermc/geyser/event/type/SessionLoadResourcePacksEventImpl.class")) {
                if (rpIn == null || !new String(rpIn.readAllBytes(), StandardCharsets.ISO_8859_1).contains("purpurbedrock.promptOptionalPacks")) {
                    throw new IllegalStateException("SessionLoadResourcePacksEventImpl missing promptOptionalPacks guard!");
                }
            }

            return "MULTIVERSION_685_TO_2225_SELFTEST_OK codecs=" + protocols.size()
                + " versions=1.21.0..26.60 dialogWaitPopup=DISABLED chunkPublisher=RELIABLE ruLocale=OK"
                + " pre818StartGame=OK attrSingleton=OK waypointFix=OK configDialogSafe=OK chunkBoundsSafe=OK"
                + " safeRemoteSkins=" + (PurpurConfig.bedrockSafeRemotePlayerSkins ? "ON" : "OFF")
                + " skullsToggle=" + (isCustomSkullsEnabled() ? "ON" : "OFF")
                + " particleCap=" + activeParticleCap
                + " mobileStaticSkulls=" + activeStaticSkullCap;
        } catch (Throwable t) {
            throw new RuntimeException("MULTIVERSION_SELFTEST_FAILED: " + t.getMessage(), t);
        }
    }

    private static String resolveAdvertiseAddressesYaml(String rawPublicAddr) {
        java.util.LinkedHashSet<String> ips = new java.util.LinkedHashSet<>();
        if (rawPublicAddr != null && !rawPublicAddr.isBlank()) {
            for (String token : rawPublicAddr.split(",")) {
                String hostOnly = token.trim();
                if (hostOnly.isEmpty()) {
                    continue;
                }
                int colonIdx = hostOnly.indexOf(':');
                if (colonIdx > 0 && hostOnly.indexOf(']') < 0) {
                    hostOnly = hostOnly.substring(0, colonIdx).trim();
                }
                try {
                    for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(hostOnly)) {
                        if (addr instanceof java.net.Inet4Address) {
                            ips.add(addr.getHostAddress());
                        }
                    }
                    if (ips.isEmpty()) {
                        String fallback = java.net.InetAddress.getByName(hostOnly).getHostAddress();
                        if (fallback != null && !fallback.isBlank()) {
                            ips.add(fallback);
                        }
                    }
                } catch (Exception ex) {
                    Bukkit.getLogger().warning("[Purpurbedrock] Не удалось определить IP для nethernet.public-address=" + hostOnly + ": " + ex.getMessage());
                }
            }
        } else if (System.getenv("P_SERVER_UUID") != null || Files.exists(Path.of("/.dockerenv"))) {
            // Auto-detect public node IPv4 on Pterodactyl / Docker containers so NetherNet WebRTC never sends 172.18.0.x
            String[] endpoints = {"https://api.ipify.org", "https://checkip.amazonaws.com", "https://icanhazip.com"};
            for (String url : endpoints) {
                try {
                    java.net.URLConnection conn = java.net.URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    try (InputStream in = conn.getInputStream()) {
                        String ip = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                        if (ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                            ips.add(ip);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (ips.isEmpty()) {
            return "[]";
        }
        Bukkit.getLogger().info("[Purpurbedrock] Публичный IP для NetherNet WebRTC (хостинг/NAT): " + String.join(", ", ips));
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String ip : ips) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(escapeYaml(ip)).append('"');
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeYaml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
