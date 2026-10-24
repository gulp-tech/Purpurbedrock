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

    public static boolean hasWindowsLoopbackWarning() {
        return windowsLoopbackWarning;
    }

    public static void initialize(DedicatedServer server) {
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

            Path engineDir = Path.of("cache", "purpurbedrock", "engine");
            Files.createDirectories(engineDir);
            System.setProperty("purpurbedrock.engineDir", engineDir.toAbsolutePath().toString());
            System.setProperty("purpurbedrock.offlineAssets",
                bedrockCfg.getInt("performance.max-visible-custom-skulls", 0) <= 0 ? "true" : "false");

            boolean lanEnabled = bedrockCfg.getBoolean("lan-discovery.enabled", true);
            int lanPort = bedrockCfg.getInt("lan-discovery.port", 7551);
            int webrtcPort = bedrockCfg.getInt("nethernet.webrtc-port", 19133);
            int lanIcePort = bedrockCfg.getInt("lan-discovery.ice-port", webrtcPort + 1);
            activeLanDiscoveryPort = lanEnabled ? lanPort : 0;

            System.setProperty("purpurbedrock.lanDiscovery", Boolean.toString(lanEnabled));
            System.setProperty("purpurbedrock.lanDiscoveryPort", Integer.toString(lanPort));
            System.setProperty("purpurbedrock.lanIcePort", Integer.toString(lanIcePort));

            syncInternalEngineConfig(bedrockCfg, server, engineDir, floodgateReady);

            if (externalGeyserJarPresent) {
                Bukkit.getLogger().info("[Purpurbedrock] Обнаружен внешний Geyser-Spigot в plugins/ — встроенный модуль пропущен.");
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
                    loaded.onLoad();
                    protocolActive = true;
                    Bukkit.getLogger().info(String.format(
                        "[Purpurbedrock] Встроенный протокол Bedrock 26.2 активен! RakNet/Signaling: %s:%d | NetherNet WebRTC: UDP %d | LAN Discovery (Миры): %s | Auth: %s",
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
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        // Always generate unlock-windows-lan.bat on Windows so same-PC Bedrock LAN works in 1 click
        Path helperBat = Path.of("unlock-windows-lan.bat");
        if (!Files.exists(helperBat)) {
            String batContent = String.join("\r\n",
                "@echo off",
                "chcp 65001 >nul",
                "net session >nul 2>&1",
                "if %errorLevel% neq 0 (",
                "    echo Запрос прав Администратора для разблокировки локальной сети Minecraft Windows 10/11...",
                "    powershell -Command \"Start-Process -FilePath '%~f0' -Verb RunAs\"",
                "    exit /b",
                ")",
                "echo [Purpurbedrock 26.2] Разблокировка UWP Loopback для Minecraft Bedrock Windows 10/11...",
                "CheckNetIsolation LoopbackExempt -a -n=\"Microsoft.MinecraftUWP_8wekyb3d8bbwe\"",
                "CheckNetIsolation LoopbackExempt -a -n=\"Microsoft.MinecraftWindowsBeta_8wekyb3d8bbwe\"",
                "echo [Purpurbedrock 26.2] Открытие локальных портов Bedrock и LAN Discovery в Брандмауэре Windows...",
                "netsh advfirewall firewall add rule name=\"Purpurbedrock UDP 19132-19134\" dir=in action=allow protocol=UDP localport=19132-19134 >nul 2>&1",
                "netsh advfirewall firewall add rule name=\"Purpurbedrock TCP 19132\" dir=in action=allow protocol=TCP localport=19132 >nul 2>&1",
                "netsh advfirewall firewall add rule name=\"Purpurbedrock LAN UDP 7551\" dir=in action=allow protocol=UDP localport=7551 >nul 2>&1",
                "echo.",
                "echo ГОТОВО! Теперь перезапустите Minecraft Bedrock — сервер сразу появится во вкладке Миры (LAN)!",
                "pause"
            ) + "\r\n";
            try {
                Files.writeString(helperBat, batContent, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
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
            "Внешний GeyserMC не требуется: протокол Bedrock, LAN Discovery (UDP 7551 + 19132),",
            "NetherNet WebRTC и поддержка всех Java-плагинов и меню встроены прямо в ядро."
        ));
        cfg.addDefault("enabled", true);
        cfg.addDefault("address", "0.0.0.0");
        cfg.addDefault("port", 19132);
        cfg.addDefault("transport", "both");
        cfg.addDefault("nethernet.webrtc-port", 19133);
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
        cfg.addDefault("performance.mtu", 1350);
        cfg.addDefault("performance.compression-level", 4);
        cfg.addDefault("performance.cache-images", 7);
        cfg.addDefault("performance.max-visible-custom-skulls", 0);
        cfg.addDefault("packs.force-resource-packs", false);
        cfg.addDefault("packs.enable-integrated-pack", false);
        cfg.options().copyDefaults(true);
        cfg.save(file);
        return cfg;
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
        String showCooldown = bedrockCfg.getString("gameplay.show-cooldown", "false");
        boolean disableScaffolding = bedrockCfg.getBoolean("gameplay.disable-bedrock-scaffolding", false);
        int mtu = bedrockCfg.getInt("performance.mtu", 1350);
        int compressionLevel = bedrockCfg.getInt("performance.compression-level", 4);
        int cacheImages = bedrockCfg.getInt("performance.cache-images", 7);
        int maxSkulls = bedrockCfg.getInt("performance.max-visible-custom-skulls", 0);
        boolean forcePacks = bedrockCfg.getBoolean("packs.force-resource-packs", false);
        boolean integratedPack = bedrockCfg.getBoolean("packs.enable-integrated-pack", false);

        // Internal engine config-version 10 (matches native Minecraft 26.2 protocol module)
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
                  advertise-addresses: []
                  https:
                    certificate: ""
                    private-key: ""
                    password: ""
                nxs:
                  endpoint: "https://signal.geyserconnect.net"
                  token: ""
                  advertise-addresses: []
            java:
              address: "127.0.0.1"
              port: %d
              auth-type: "%s"
              use-haproxy-protocol: false
              haproxy-protocol-whitelisted-ips:
                - "127.0.0.1"
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
              custom-model-data-mappings: true
              command-suggestions: %s
              unusable-space-block: "minecraft:barrier"
              show-cooldown: "%s"
              show-coordinates: true
              disable-bedrock-scaffolding: %s
              emotes-enabled: true
              xbox-achievements-enabled: false
              max-visible-custom-skulls: %d
              custom-skull-render-distance: 24
              allow-custom-skulls: %s
              force-resource-packs: %s
              enable-integrated-pack: %s
              forward-player-ping: true
               nether-roof-workaround: true
            default-locale: ru_ru
            log-player-ip-addresses: true
            saved-user-logins: []
            pending-authentication-timeout: 60
            notify-on-new-bedrock-update: false
            advanced:
              cache-images: %d
              floodgate-key-file: "key.pem"
              bedrock:
                broadcast-port: 0
                compression-level: %d
                use-haproxy-protocol: false
                haproxy-protocol-whitelisted-ips: []
                signaling-trusted-proxies: []
                mtu: %d
                validate-bedrock-login: %s
              java:
                use-direct-connection: true
                disable-compression: true
              nxs:
                assisted-joins: false
                connection-checks: false
                stun-discovery: true
                deregister-on-shutdown: true
                location: ""
            debug-mode: false
            config-version: 10
            """.formatted(
            escapeYaml(bindAddress),
            bedrockPort,
            webrtcPort,
            transport,
            javaPort,
            escapeYaml(resolvedAuth),
            motd1,
            motd2,
            maxPlayers,
            motd1,
            commandSuggestions,
            escapeYaml(showCooldown),
            disableScaffolding,
            maxSkulls,
            maxSkulls > 0,
            forcePacks,
            integratedPack,
            cacheImages,
            compressionLevel,
            mtu,
            validateBedrockLogin
        ).replace("              nether-roof-workaround:", "             nether-roof-workaround:");
        // Fix indentation cleanly on nether-roof-workaround
        internalYaml = internalYaml.replace("             nether-roof-workaround:", "              nether-roof-workaround:");

        Files.createDirectories(engineDir);
        Files.writeString(engineDir.resolve("config.yml"), internalYaml, StandardCharsets.UTF_8);
    }

    private static String escapeYaml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
