package org.purpurmc.purpur.bedrock;

import com.mojang.logging.LogUtils;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.provider.source.PluginFlagProviderSource;
import io.papermc.paper.plugin.provider.type.paper.PaperPluginParent;
import io.papermc.paper.plugin.provider.type.spigot.SpigotPluginProvider;
import io.papermc.paper.plugin.util.EntrypointUtil;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import joptsimple.OptionSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Automatically extracts and registers the embedded Geyser-Spigot Bedrock bridge
 * on startup so Bedrock Edition players (1.21.100 - 26.52) can join out of the box
 * without requiring manual setup, while still allowing Java plugins in plugins/.
 */
public final class BedrockBridgeBootstrap {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String EMBEDDED_RESOURCE = "META-INF/purpurbedrock/Geyser-Spigot.jar";

    private static volatile boolean embeddedEnabled = true;
    private static volatile int defaultBedrockPort = 19132;
    private static volatile boolean embeddedBridgeActive = false;
    private static volatile boolean externalGeyserDetected = false;

    private BedrockBridgeBootstrap() {
    }

    public static void initEarlyConfig(final YamlConfiguration purpurConfig) {
        boolean cfgEnabled = purpurConfig.getBoolean("settings.bedrock.embedded-geyser.enabled", true);
        String sysProp = System.getProperty("purpurbedrock.embeddedGeyser");
        if (sysProp != null) {
            cfgEnabled = Boolean.parseBoolean(sysProp);
        }
        embeddedEnabled = cfgEnabled;

        int port = purpurConfig.getInt("settings.bedrock.embedded-geyser.port", 19132);
        String envPort = System.getenv("BEDROCK_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String propPort = System.getProperty("purpurbedrock.port");
        if (propPort != null && !propPort.isBlank()) {
            try {
                port = Integer.parseInt(propPort.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        defaultBedrockPort = port;
    }

    public static boolean isEmbeddedBridgeActive() {
        return embeddedBridgeActive;
    }

    public static boolean isExternalGeyserDetected() {
        return externalGeyserDetected;
    }

    public static int getDefaultBedrockPort() {
        return defaultBedrockPort;
    }

    public static void registerEmbeddedBridgeIfNeeded(final OptionSet options, final Path pluginsDir) {
        if (!embeddedEnabled) {
            LOGGER.info("[Purpurbedrock] Embedded Geyser bridge is disabled via configuration.");
            return;
        }

        AtomicBoolean userGeyserPresent = new AtomicBoolean(false);
        AtomicBoolean floodgatePresent = new AtomicBoolean(false);
        LaunchEntryPointHandler.INSTANCE.getStorage().forEach((entrypoint, storage) -> {
            storage.getRegisteredProviders().forEach(provider -> {
                String name = null;
                if (provider instanceof SpigotPluginProvider legacy) {
                    name = legacy.getMeta().getName();
                } else if (provider instanceof PaperPluginParent.PaperServerPluginProvider paper) {
                    name = paper.getMeta().getName();
                }
                if (name != null) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("geyser-spigot") || lower.equals("geyser-paper") || lower.equals("geyser")) {
                        userGeyserPresent.set(true);
                    } else if (lower.equals("floodgate")) {
                        floodgatePresent.set(true);
                    }
                }
            });
        });

        if (userGeyserPresent.get()) {
            externalGeyserDetected = true;
            LOGGER.info("[Purpurbedrock] Detected user-provided Geyser in plugins/ — skipping embedded bridge.");
            return;
        }

        URL resourceUrl = findEmbeddedGeyserResource();
        if (resourceUrl == null) {
            LOGGER.info("[Purpurbedrock] No embedded Geyser resource bundled in this build (dev mode); place Geyser-Spigot.jar in plugins/ if needed.");
            return;
        }

        try {
            Path cacheDir = Path.of("cache", "purpurbedrock");
            Files.createDirectories(cacheDir);
            Path targetJar = cacheDir.resolve("Geyser-Spigot.jar");

            URLConnection conn = resourceUrl.openConnection();
            long expectedLength = conn.getContentLengthLong();
            boolean needExtract = !Files.isRegularFile(targetJar)
                || (expectedLength > 0 && Files.size(targetJar) != expectedLength);

            if (needExtract) {
                Path tempJar = cacheDir.resolve("Geyser-Spigot.jar.tmp");
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tempJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Purpurbedrock] Extracted embedded Bedrock bridge to {}", targetJar);
            }

            ensureDefaultGeyserConfig(
                pluginsDir.resolve("Geyser-Spigot").resolve("config.yml"),
                defaultBedrockPort,
                floodgatePresent.get()
            );

            EntrypointUtil.registerProvidersFromSource(PluginFlagProviderSource.INSTANCE, List.of(targetJar));
            embeddedBridgeActive = true;
            LOGGER.info("[Purpurbedrock] Embedded Bedrock bridge (Geyser 1.21.100 - 26.52) registered on UDP port {} (Floodgate integration: {}).",
                defaultBedrockPort, floodgatePresent.get() ? "ENABLED" : "standalone");
        } catch (Exception ex) {
            LOGGER.error("[Purpurbedrock] Failed to initialize embedded Bedrock bridge", ex);
        }
    }

    private static URL findEmbeddedGeyserResource() {
        URL url = ClassLoader.getSystemClassLoader().getResource(EMBEDDED_RESOURCE);
        if (url != null) {
            return url;
        }
        return BedrockBridgeBootstrap.class.getClassLoader().getResource(EMBEDDED_RESOURCE);
    }

    private static void ensureDefaultGeyserConfig(final Path configPath, final int bedrockPort, final boolean useFloodgate) throws IOException {
        if (Files.exists(configPath)) {
            if (useFloodgate) {
                String existing = Files.readString(configPath, StandardCharsets.UTF_8);
                if (existing.contains("Purpurbedrock") && existing.contains("auth-type: offline")) {
                    String updated = existing
                        .replace("auth-type: offline", "auth-type: floodgate")
                        .replace("floodgate-key-file: key.pem", "floodgate-key-file: ../floodgate/key.pem");
                    Files.writeString(configPath, updated, StandardCharsets.UTF_8);
                    LOGGER.info("[Purpurbedrock] Automatically linked embedded Geyser config with Floodgate (auth-type: floodgate).");
                }
            }
            return;
        }
        Files.createDirectories(configPath.getParent());
        String authType = useFloodgate ? "floodgate" : "offline";
        String floodgateKeyPath = useFloodgate ? "../floodgate/key.pem" : "key.pem";
        String defaultConfig = """
            # ------------------------------------------------------------
            # Purpurbedrock — автоматическая конфигурация Bedrock-моста (Geyser)
            # Поддерживаются клиенты Bedrock 1.21.100 – 26.52 (протоколы 827 – 2193).
            # ------------------------------------------------------------

            bedrock:
              address: 0.0.0.0
              port: %d
              # Для включения NetherNet вместе с RakNet выделите второй UDP-порт в webrtc-port
              # и установите transport: both (также должен быть открыт TCP на port для сигнализации).
              webrtc-port: 0
              transport: raknet
              clone-remote-port: false
              signaling:
                mode: builtin
                advertise-addresses: []
                stun-discovery: true
                builtin:
                  https:
                    certificate: ''
                    private-key: ''
                    password: ''
                nxs:
                  token: ''
                  endpoint: https://agent.warden.cloud

            java:
              # Вход с Java Edition заблокирован на уровне ядра Purpurbedrock (BedrockGatekeeper).
              # Если установлен плагин Floodgate — автоматически используется auth-type: floodgate.
              auth-type: %s

            motd:
              primary-motd: §b✦ §fPurpur§dbedrock §b✦
              secondary-motd: §7Bedrock §8| §b1.21.100 – 26.52
              passthrough-motd: true
              max-players: 100
              passthrough-player-counts: true
              integrated-ping-passthrough: true
              ping-passthrough-interval: 3

            gameplay:
              server-name: Purpurbedrock
              # Боёвка Bedrock (без кулдауна 1.9) включена нативно в ядре Purpurbedrock
              cooldown-type: disabled
              command-suggestions: true
              show-coordinates: true
              disable-bedrock-scaffolding: false
              nether-roof-workaround: false
              emotes-enabled: true
              block-legacy-codes: true
              unusable-space-block: minecraft:barrier
              enable-custom-content: true
              force-resource-packs: true
              enable-integrated-pack: true
              forward-player-ping: false
              xbox-achievements-enabled: false
              max-visible-custom-skulls: 128
              custom-skull-render-distance: 32

            default-locale: system
            log-player-ip-addresses: true

            saved-user-logins:
              - ThisExampleUsernameShouldBeLongEnoughToNeverBeAnXboxUsername

            pending-authentication-timeout: 120
            notify-on-new-bedrock-update: false

            advanced:
              cache-images: 7
              scoreboard-packet-threshold: 20
              add-team-suggestions: true
              resource-pack-urls: []
              floodgate-key-file: %s
              java:
                use-haproxy-protocol: false
                use-direct-connection: true
                disable-compression: true
              bedrock:
                broadcast-port: 0
                compression-level: 6
                use-haproxy-protocol: false
                haproxy-protocol-whitelisted-ips: []
                signaling-trusted-proxies: []
                use-waterdogpe-forwarding: false
                mtu: 1200
                validate-bedrock-login: true
                nethernet-debug: false
              nxs:
                assisted-joins: false
                connection-checks: true
                location: {}
                deregister-on-shutdown: false

            debug-mode: false
            config-version: 11
            """.formatted(bedrockPort, authType, floodgateKeyPath);
        Files.writeString(configPath, defaultConfig, StandardCharsets.UTF_8);
    }
}
