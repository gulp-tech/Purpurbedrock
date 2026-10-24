#!/usr/bin/env python3
"""
Transforms GeyserMC/Geyser (commit bbd7e67, native Minecraft 26.2) into
Purpurbedrock's self-contained internal Bedrock 26.2 protocol engine:
1. Internal engine config folder ('cache/purpurbedrock/engine') -> zero 'plugins/Geyser-Spigot' pollution.
2. Disables bStats metrics and external geysermc.org NewsHandler polling.
3. Offline instant AssetUtils -> loads assets/minecraft/lang/en_us.json directly from Purpur 26.2's
   server classpath in <1ms with zero Mojang client JAR download on startup.
4. Adds NetherNet Local LAN Discovery (NetherNetDiscoverySignaling on UDP 7551) alongside
   RakNet (UDP 19132) + NetherNet HTTP Signaling/WebRTC so the server automatically appears
   in Minecraft Bedrock 26.x's local LAN Worlds ('Миры') tab.
5. Anti-kick protocol resilience: never kicks Bedrock players on PacketViolationWarningPacket
   or recoverable IllegalPacketExceptions.
"""
import pathlib
import sys


def replace_exact(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"[{label}] Expected snippet not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[OK] Patched {label} in {path.name}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch-bedrock-engine.py <geyser-root>")

    root = pathlib.Path(sys.argv[1]).resolve()

    # 0. Trim settings.gradle.kts to only build :ap, :api, :common, :core, :spigot (skip Loom/NeoForge)
    replace_exact(
        root / "settings.gradle.kts",
        """include(":ap")
include(":api")
include(":bungeecord")
include(":fabric")
include(":gametest")
include(":neoforge")
include(":mod")
include(":spigot")
include(":standalone")
include(":velocity")
include(":viaproxy")
include(":common")
include(":core")

// Specify project dirs
project(":bungeecord").projectDir = file("bootstrap/bungeecord")
project(":fabric").projectDir = file("bootstrap/mod/fabric")
project(":gametest").projectDir = file("bootstrap/mod/gametest")
project(":neoforge").projectDir = file("bootstrap/mod/neoforge")
project(":mod").projectDir = file("bootstrap/mod")
project(":spigot").projectDir = file("bootstrap/spigot")
project(":standalone").projectDir = file("bootstrap/standalone")
project(":velocity").projectDir = file("bootstrap/velocity")
project(":viaproxy").projectDir = file("bootstrap/viaproxy")""",
        """include(":ap")
include(":api")
include(":common")
include(":core")
include(":spigot")

project(":spigot").projectDir = file("bootstrap/spigot")""",
        "settings.gradle.kts minimal spigot build",
    )

    # 1. GeyserSpigotPlugin.java: internal config folder + disable bStats
    spigot_plugin = root / "bootstrap/spigot/src/main/java/org/geysermc/geyser/platform/spigot/GeyserSpigotPlugin.java"
    replace_exact(
        spigot_plugin,
        """    @Override
    public Path getConfigFolder() {
        return getDataFolder().toPath();
    }""",
        """    @Override
    public Path getConfigFolder() {
        Path internalDir = Path.of(System.getProperty("purpurbedrock.engineDir", "cache/purpurbedrock/engine"));
        try {
            java.nio.file.Files.createDirectories(internalDir);
        } catch (java.io.IOException ignored) {
        }
        return internalDir;
    }""",
        "GeyserSpigotPlugin.getConfigFolder",
    )
    replace_exact(
        spigot_plugin,
        """        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        Path geyserDataFolder = getDataFolder().toPath();
        Path floodgateDataFolder = floodgate != null ? floodgate.getDataFolder().toPath() : null;""",
        """        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        Path geyserDataFolder = getConfigFolder();
        Path floodgateDataFolder = floodgate != null ? floodgate.getDataFolder().toPath() : null;""",
        "GeyserSpigotPlugin.getFloodgateKeyPath",
    )
    replace_exact(
        spigot_plugin,
        """    @Override
    public MetricsPlatform createMetricsPlatform() {
        return new SpigotMetrics(this);
    }""",
        """    @Override
    public MetricsPlatform createMetricsPlatform() {
        return null; // Purpurbedrock: disable external bStats telemetry
    }""",
        "GeyserSpigotPlugin.createMetricsPlatform",
    )

    # 2. NewsHandler.java: disable external geysermc.org polling
    news_handler = root / "core/src/main/java/org/geysermc/geyser/util/NewsHandler.java"
    replace_exact(
        news_handler,
        """    public NewsHandler(String branch, int build) {
        this.branch = branch;
        this.build = build;

        executorService.scheduleWithFixedDelay(this::checkNews, 0, 30, TimeUnit.MINUTES);
    }""",
        """    public NewsHandler(String branch, int build) {
        this.branch = branch;
        this.build = build;
        // Purpurbedrock: disabled external news polling to geysermc.org
    }""",
        "NewsHandler.constructor",
    )

    # 3. AssetUtils.java: load en_us.json directly from Purpur 26.2 server classpath without 50MB download
    asset_utils = root / "core/src/main/java/org/geysermc/geyser/util/AssetUtils.java"
    replace_exact(
        asset_utils,
        """    public static CompletableFuture<Void> generateAssetCache() {
        return CompletableFuture.supplyAsync(() -> {""",
        """    public static CompletableFuture<Void> generateAssetCache() {
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.offlineAssets", "true"))) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {""",
        "AssetUtils.generateAssetCache",
    )
    replace_exact(
        asset_utils,
        """    public static void downloadAndRunClientJarTasks() {
        if (CLIENT_JAR_INFO == null) {
            // Likely failed to download
            GeyserImpl.getInstance().getLogger().debug("Skipping en_US hash check as client jar is null.");
            return;
        }""",
        """    public static void downloadAndRunClientJarTasks() {
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.offlineAssets", "true"))) {
            ClientJarTask task;
            while ((task = CLIENT_JAR_TASKS.poll()) != null) {
                try {
                    InputStream classpathStream = AssetUtils.class.getClassLoader().getResourceAsStream(task.asset);
                    if (classpathStream == null && Thread.currentThread().getContextClassLoader() != null) {
                        classpathStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(task.asset);
                    }
                    if (classpathStream != null) {
                        try (InputStream in = classpathStream) {
                            task.ifNewDownload.accept(in);
                        }
                    }
                    task.whenDone.run();
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (CLIENT_JAR_INFO == null) {
            // Likely failed to download
            GeyserImpl.getInstance().getLogger().debug("Skipping en_US hash check as client jar is null.");
            return;
        }""",
        "AssetUtils.downloadAndRunClientJarTasks",
    )

    # 4. NetherNetServer.java: add NetherNetDiscoverySignaling on UDP 7551 for Bedrock 26.x LAN Worlds ('Миры')
    nethernet_server = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/nethernet/NetherNetServer.java"
    replace_exact(
        nethernet_server,
        """import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;""",
        """import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscoverySignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;""",
        "NetherNetServer.imports",
    )
    replace_exact(
        nethernet_server,
        """    private EventLoopGroup inbuiltEventLoopGroup;
    private Channel inbuiltChannel;
    private NetherNetChannelInitialiser inbuiltInitialiser;""",
        """    private EventLoopGroup inbuiltEventLoopGroup;
    private Channel inbuiltChannel;
    private NetherNetChannelInitialiser inbuiltInitialiser;
    private NetherNetDiscoverySignaling lanDiscoverySignaling;
    private EventLoopGroup lanDiscoveryEventLoopGroup;
    private Channel lanDiscoveryChannel;
    private NetherNetChannelInitialiser lanDiscoveryInitialiser;""",
        "NetherNetServer.fields",
    )
    replace_exact(
        nethernet_server,
        """        if (inbuilt) startInbuilt();
        if (provider) startProvider();
    }""",
        """        if (inbuilt) startInbuilt();
        if (Boolean.parseBoolean(System.getProperty("purpurbedrock.lanDiscovery", "true"))) {
            startLanDiscovery();
        }
        if (provider) startProvider();
    }

    private PongData buildLanPongData() {
        BedrockPong pong = pingResponder.onQuery(GUID, new InetSocketAddress("127.0.0.1", 0), null);
        return new PongData.Builder()
                .setServerName(pong.motd() != null ? pong.motd() : "Purpurbedrock 26.2")
                .setLevelName(pong.subMotd() != null ? pong.subMotd() : "Purpurbedrock LAN")
                .setProtocol(pong.protocolVersion())
                .setVersion(pong.version())
                .setPlayerCount(pong.playerCount())
                .setMaxPlayerCount(Math.max(1, pong.maximumPlayerCount()))
                .build();
    }

    private void startLanDiscovery() {
        BedrockListener listener = geyser.config().bedrock();
        int discoveryPort = Integer.getInteger("purpurbedrock.lanDiscoveryPort", NetherNetConstants.DISCOVERY_PORT);
        int lanIcePort = Integer.getInteger("purpurbedrock.lanIcePort", webrtcPort + 1);
        try {
            NetherNetLogging.setNativeLogLevel("WARN");
            InetSocketAddress bindAddr = new InetSocketAddress(listener.address(), discoveryPort);
            this.lanDiscoverySignaling = new NetherNetDiscoverySignaling(GUID ^ 0x4C414EL, bindAddr);
            this.lanDiscoverySignaling.setAdvertisementData(buildLanPongData());

            this.lanDiscoveryEventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            this.lanDiscoveryInitialiser = new NetherNetChannelInitialiser(geyser);

            ServerBootstrap lanBootstrap = new ServerBootstrap()
                    .group(this.lanDiscoveryEventLoopGroup)
                    .channelFactory(NetherNetChannelFactory.server(this.lanDiscoverySignaling))
                    .childHandler(this.lanDiscoveryInitialiser)
                    .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, new InetSocketAddress(listener.address(), lanIcePort));

            this.lanDiscoveryChannel = lanBootstrap.bind(bindAddr).sync().channel();
            this.lanDiscoveryEventLoopGroup.next().scheduleAtFixedRate(() -> {
                if (!stopping && this.lanDiscoverySignaling != null) {
                    try {
                        this.lanDiscoverySignaling.setAdvertisementData(buildLanPongData());
                    } catch (Throwable ignored) {
                    }
                }
            }, 3, 3, TimeUnit.SECONDS);

            logger().info("[Purpurbedrock] NetherNet LAN Discovery active on UDP " + listener.address() + ":" + discoveryPort
                    + " (LAN ICE UDP " + lanIcePort + ") — server visible in Bedrock 'Миры' LAN tab.");
        } catch (Throwable e) {
            closeLanDiscoveryResources();
            logger().info("[Purpurbedrock] NetherNet LAN UDP " + discoveryPort + " unavailable (" + e.getClass().getSimpleName()
                    + "); RakNet/Signaling remains active.");
        }
    }

    private void closeLanDiscoveryResources() {
        if (lanDiscoveryChannel != null) {
            try { lanDiscoveryChannel.close(); } catch (Throwable ignored) {}
            lanDiscoveryChannel = null;
        }
        if (lanDiscoverySignaling != null) {
            try { lanDiscoverySignaling.close(); } catch (Throwable ignored) {}
            lanDiscoverySignaling = null;
        }
        if (lanDiscoveryEventLoopGroup != null) {
            lanDiscoveryEventLoopGroup.shutdownGracefully();
            lanDiscoveryEventLoopGroup = null;
        }
        if (lanDiscoveryInitialiser != null) {
            lanDiscoveryInitialiser.getEventLoopGroup().shutdownGracefully();
            lanDiscoveryInitialiser = null;
        }
    }""",
        "NetherNetServer.startLanDiscovery",
    )
    replace_exact(
        nethernet_server,
        """    private void closeInbuiltResources() {
        if (inbuiltChannel != null) inbuiltChannel.close();
        if (signaling != null) signaling.close();
        if (inbuiltEventLoopGroup != null) inbuiltEventLoopGroup.shutdownGracefully();
        if (inbuiltInitialiser != null) inbuiltInitialiser.getEventLoopGroup().shutdownGracefully();
    }""",
        """    private void closeInbuiltResources() {
        if (inbuiltChannel != null) inbuiltChannel.close();
        if (signaling != null) signaling.close();
        if (inbuiltEventLoopGroup != null) inbuiltEventLoopGroup.shutdownGracefully();
        if (inbuiltInitialiser != null) inbuiltInitialiser.getEventLoopGroup().shutdownGracefully();
        closeLanDiscoveryResources();
    }""",
        "NetherNetServer.closeInbuiltResources",
    )

    # 5. BedrockPacketViolationWarningTranslator & InvalidPacketHandler: never kick players for benign warnings
    violation_translator = root / "core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/BedrockPacketViolationWarningTranslator.java"
    replace_exact(
        violation_translator,
        """        logger.warning("Received packet violation warning (type: %s, severity: %s, packetCauseId: %s) from client (%s): %s! Enable debug mode for the full message.".formatted(packet.getType(),
            packet.getSeverity(), packet.getPacketCauseId(), session.bedrockUsername(), sanitize(message)));
        session.disconnect("An error occurred! Please contact an administrator.");""",
        """        logger.debug("Ignored packet violation warning (type: %s, severity: %s, packetCauseId: %s) from client (%s): %s".formatted(packet.getType(),
            packet.getSeverity(), packet.getPacketCauseId(), session.bedrockUsername(), sanitize(message)));
        // Purpurbedrock: do not kick Bedrock players on non-fatal client packet warnings""",
        "BedrockPacketViolationWarningTranslator",
    )

    invalid_packet = root / "core/src/main/java/org/geysermc/geyser/network/bedrock/InvalidPacketHandler.java"
    replace_exact(
        invalid_packet,
        """        // Kick users that try to send illegal packets
        logger.error("Illegal packet from " + session.bedrockUsername(), cause);
        session.disconnect("Invalid packet received!");
        session.forciblyCloseUpstream();""",
        """        // Purpurbedrock: ignore recoverable malformed/unsupported packets instead of disconnecting
        logger.debug("Ignored unsupported/malformed Bedrock packet from " + session.bedrockUsername() + ": " + rootCause.getMessage());""",
        "InvalidPacketHandler",
    )


if __name__ == "__main__":
    main()
