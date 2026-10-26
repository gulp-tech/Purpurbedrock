package org.purpurmc.purpur.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.purpurmc.purpur.PurpurConfig;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Built-in PlaceholderAPI provider and Smart Bedrock Device Analyzer inside Purpurbedrock 26.2.
 *
 * Classifies every Bedrock player in real time into the 6 canonical device categories:
 * <ul>
 *   <li>{@code Windows} ({@code windows})</li>
 *   <li>{@code Android} ({@code android})</li>
 *   <li>{@code IOS} ({@code ios})</li>
 *   <li>{@code Xbox} ({@code xbox})</li>
 *   <li>{@code PS 4-5} ({@code ps4_5})</li>
 *   <li>{@code Клавиатура + мышь} ({@code phone_kbm} — OTG / Bluetooth keyboard + mouse connected to Android / iOS)</li>
 * </ul>
 */
public final class BedrockPlaceholderHook {
    private static final String EXPANSION_CLASS_NAME = "org.purpurmc.purpur.bedrock.papi.PurpurBedrockPapiExpansion";
    private static final long PHONE_KBM_STICKY_MS = 45_000L;
    private static volatile boolean registered = false;

    // Sticky memory so a phone KBM player tapping the touch screen once cannot hide KBM in TAB
    private static final Map<UUID, Long> recentMobileKbmUntilMs = new ConcurrentHashMap<>();

    // Cached Geyser reflection handles (resolved lazily once against Geyser-Spigot's ClassLoader)
    private static volatile ClassLoader cachedGeyserLoader;
    private static volatile Method geyserGetInstance;
    private static volatile Method geyserConnectionByUuid;
    private static volatile Method sessionPlatform;
    private static volatile Method sessionGetClientData;
    private static volatile Method clientDataGetDeviceOs;
    private static volatile Method clientDataGetDeviceModel;
    private static volatile Method clientDataGetCurrentInputMode;
    private static volatile Method clientDataGetDefaultInputMode;
    private static volatile Method sessionInputMode;
    private static volatile Method sessionVersion;
    private static volatile Method sessionPing;
    private static volatile Method sessionProtocolVersion;
    private static volatile Method sessionXuid;
    private static volatile Method sessionUiProfile;
    private static volatile Method gameProtocolGetBedrockCodec;
    private static volatile Method codecGetMinecraftVersion;

    private BedrockPlaceholderHook() {
    }

    public static boolean isRegistered() {
        return registered;
    }

    public static void initAfterPlugins() {
        if (!PurpurConfig.bedrockPlaceholdersEnabled) {
            return;
        }
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi != null && papi.isEnabled()) {
            tryRegisterWithPlaceholderApi(papi);
        }
    }

    public static synchronized boolean tryRegisterWithPlaceholderApi(@Nullable Plugin papiPlugin) {
        if (!PurpurConfig.bedrockPlaceholdersEnabled || papiPlugin == null || !papiPlugin.isEnabled()) {
            return false;
        }
        try {
            ClassLoader papiLoader = papiPlugin.getClass().getClassLoader();
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", true, papiLoader);

            byte[] expansionBytes = generateExpansionBytecode();
            PapiBridgeClassLoader bridgeLoader = new PapiBridgeClassLoader(
                papiLoader,
                BedrockPlaceholderHook.class.getClassLoader()
            );
            Class<?> expansionClass = bridgeLoader.defineExpansion(EXPANSION_CLASS_NAME, expansionBytes);
            Object expansionInstance = expansionClass.getDeclaredConstructor().newInstance();
            Object result = expansionClass.getMethod("register").invoke(expansionInstance);
            boolean ok = Boolean.TRUE.equals(result);
            if (ok) {
                registered = true;
                Bukkit.getLogger().info(
                    "[Purpurbedrock] Встроенные плейсхолдеры %purpurbedrock_*% зарегистрированы в PlaceholderAPI "
                        + "(Windows, Android, IOS, Xbox, PS 4-5, Клавиатура + мышь)!"
                );
            }
            return ok;
        } catch (Throwable t) {
            Bukkit.getLogger().log(
                Level.WARNING,
                "[Purpurbedrock] Не удалось зарегистрировать встроенное расширение %purpurbedrock_*% в PlaceholderAPI",
                t
            );
            return false;
        }
    }

    public static byte[] generateExpansionBytecode() {
        ClassDesc cdExpansion = ClassDesc.of("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        ClassDesc cdImpl = ClassDesc.of(EXPANSION_CLASS_NAME);
        ClassDesc cdHook = ClassDesc.of(BedrockPlaceholderHook.class.getName());
        ClassDesc cdOfflinePlayer = ClassDesc.of("org.bukkit.OfflinePlayer");
        ClassDesc cdPlayer = ClassDesc.of("org.bukkit.entity.Player");

        MethodTypeDesc voidDesc = MethodTypeDesc.of(ConstantDescs.CD_void);
        MethodTypeDesc stringNoArgDesc = MethodTypeDesc.of(ConstantDescs.CD_String);
        MethodTypeDesc boolNoArgDesc = MethodTypeDesc.of(ConstantDescs.CD_boolean);
        MethodTypeDesc onRequestDesc = MethodTypeDesc.of(ConstantDescs.CD_String, cdOfflinePlayer, ConstantDescs.CD_String);
        MethodTypeDesc onPlayerDesc = MethodTypeDesc.of(ConstantDescs.CD_String, cdPlayer, ConstantDescs.CD_String);

        return ClassFile.of().build(cdImpl, cb -> {
            cb.withVersion(ClassFile.JAVA_21_VERSION, 0);
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withSuperclass(cdExpansion);

            cb.withMethodBody(ConstantDescs.INIT_NAME, voidDesc, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(cdExpansion, ConstantDescs.INIT_NAME, voidDesc);
                code.return_();
            });

            cb.withMethodBody("getIdentifier", stringNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.ldc("purpurbedrock");
                code.areturn();
            });

            cb.withMethodBody("getAuthor", stringNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.ldc("Purpurbedrock");
                code.areturn();
            });

            cb.withMethodBody("getVersion", stringNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.ldc("26.2");
                code.areturn();
            });

            cb.withMethodBody("persist", boolNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.iconst_1();
                code.ireturn();
            });

            cb.withMethodBody("canRegister", boolNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.iconst_1();
                code.ireturn();
            });

            cb.withMethodBody("onRequest", onRequestDesc, ClassFile.ACC_PUBLIC, code -> {
                code.aload(1);
                code.aload(2);
                code.invokestatic(cdHook, "resolveOffline", onRequestDesc);
                code.areturn();
            });

            cb.withMethodBody("onPlaceholderRequest", onPlayerDesc, ClassFile.ACC_PUBLIC, code -> {
                code.aload(1);
                code.aload(2);
                code.invokestatic(cdHook, "resolveOnline", onPlayerDesc);
                code.areturn();
            });
        });
    }

    /**
     * Direct public API for Java plugins (VoidTab, PurpurBedrockAC, scoreboards, etc.).
     * Returns one of: {@code Windows}, {@code Android}, {@code IOS}, {@code Xbox}, {@code PS 4-5}, {@code Клавиатура + мышь}.
     */
    public static String analyzeDevice(@Nullable Player player) {
        if (player == null) {
            return "—";
        }
        String res = resolveOnline(player, "device");
        return res != null ? res : PurpurConfig.bedrockPlaceholderDeviceAndroid;
    }

    /**
     * Direct public API returning machine-readable category ID:
     * {@code windows}, {@code android}, {@code ios}, {@code xbox}, {@code ps4_5}, {@code phone_kbm}.
     */
    public static String analyzeDeviceId(@Nullable Player player) {
        if (player == null) {
            return "unknown";
        }
        String res = resolveOnline(player, "device_id");
        return res != null ? res : "android";
    }

    /**
     * Returns {@code true} if the player is on a mobile OS (Android / iOS) using Keyboard + Mouse.
     */
    public static boolean isPhoneKeyboardMouse(@Nullable Player player) {
        return player != null && "phone_kbm".equals(analyzeDeviceId(player));
    }

    /**
     * Device-aware rendering/network profile used by built-in chunk streaming and optional plugins.
     * It deliberately uses only reliable Bedrock metadata and live RTT; it never guesses device RAM.
     */
    public static String analyzePerformanceProfile(@Nullable Player player) {
        if (player == null) {
            return "SAFE";
        }
        String deviceId = analyzeDeviceId(player);
        int ping = resolvePingMillis(findGeyserSession(player.getUniqueId()), player);
        return classifyPerformanceProfile(deviceId, ping);
    }

    public static String classifyPerformanceProfile(@Nullable String deviceId, int ping) {
        String id = deviceId == null ? "unknown" : deviceId;
        if (ping >= 220 || "android".equals(id) || "ios".equals(id) || "phone_kbm".equals(id)) {
            return "SAFE";
        }
        if (ping >= 140 || "xbox".equals(id) || "ps4_5".equals(id) || "unknown".equals(id)) {
            return "BALANCED";
        }
        return "HIGH";
    }

    public static @Nullable String resolveOffline(@Nullable OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }
        Player online = (player != null && player.isOnline()) ? player.getPlayer() : null;
        UUID uuid = player != null ? player.getUniqueId() : null;
        return resolveByUuid(uuid, online, params);
    }

    public static @Nullable String resolveOnline(@Nullable Player player, String params) {
        if (params == null) {
            return null;
        }
        UUID uuid = player != null ? player.getUniqueId() : null;
        return resolveByUuid(uuid, player, params);
    }

    public static @Nullable String resolveByUuid(@Nullable UUID uuid, @Nullable Player onlinePlayer, String rawParams) {
        String key = rawParams.trim().toLowerCase(Locale.ROOT);
        Object session = uuid != null ? findGeyserSession(uuid) : null;

        return switch (key) {
            case "device" -> {
                if (session != null) {
                    yield classifySessionDeviceLabel(uuid, session);
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                if (fg != null) {
                    yield classifyDeviceLabel(uuid, fg.deviceOs, fg.inputMode, "UNKNOWN", "");
                }
                yield onlinePlayer != null ? PurpurConfig.bedrockPlaceholderDeviceAndroid : "—";
            }
            case "device_id" -> {
                if (session != null) {
                    yield classifySessionDeviceId(uuid, session);
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                if (fg != null) {
                    yield classifyDeviceId(uuid, fg.deviceOs, fg.inputMode, "UNKNOWN", "");
                }
                yield onlinePlayer != null ? "android" : "unknown";
            }
            case "is_phone_kbm", "phone_kbm" -> {
                if (session != null) {
                    yield Boolean.toString("phone_kbm".equals(classifySessionDeviceId(uuid, session)));
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                if (fg != null) {
                    yield Boolean.toString("phone_kbm".equals(classifyDeviceId(uuid, fg.deviceOs, fg.inputMode, "UNKNOWN", "")));
                }
                yield "false";
            }
            case "device_model", "model" -> {
                if (session != null) {
                    String model = readDeviceModel(session);
                    yield model.isEmpty() ? classifySessionDeviceLabel(uuid, session) : model;
                }
                yield "";
            }
            case "input" -> {
                if (session != null) {
                    yield formatInput(readInputCode(session), readPlatformCode(session));
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                if (fg != null) {
                    yield formatInput(fg.inputMode, fg.deviceOs);
                }
                yield onlinePlayer != null ? PurpurConfig.bedrockPlaceholderInputTouch : "—";
            }
            case "version" -> {
                if (session != null) {
                    yield readClientVersion(session);
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                if (fg != null && fg.version != null && !fg.version.isBlank()) {
                    yield fg.version;
                }
                yield onlinePlayer != null ? Bukkit.getMinecraftVersion() : "—";
            }
            case "ping" -> Integer.toString(resolvePingMillis(session, onlinePlayer));
            case "performance", "performance_profile", "render_profile" -> {
                String deviceId;
                if (session != null) {
                    deviceId = classifySessionDeviceId(uuid, session);
                } else {
                    FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                    deviceId = fg != null ? classifyDeviceId(uuid, fg.deviceOs, fg.inputMode, "UNKNOWN", "") : "android";
                }
                int ping = resolvePingMillis(session, onlinePlayer);
                yield classifyPerformanceProfile(deviceId, ping);
            }
            case "ping_colored" -> {
                int ping = resolvePingMillis(session, onlinePlayer);
                String color = ping <= 60 ? "§a" : (ping <= 130 ? "§e" : "§c");
                yield color + ping + " мс";
            }
            case "os", "device_raw" -> {
                if (session != null) {
                    yield formatRawOsName(readPlatformCode(session));
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                yield fg != null ? formatRawOsName(fg.deviceOs) : "Unknown";
            }
            case "protocol" -> {
                if (session != null) {
                    int proto = readProtocolVersion(session);
                    if (proto > 0) {
                        yield Integer.toString(proto);
                    }
                }
                yield "0";
            }
            case "ui" -> {
                if (session != null) {
                    yield readUiProfile(session);
                }
                yield "Classic";
            }
            case "xuid" -> {
                if (session != null) {
                    yield readXuid(session);
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                yield fg != null && fg.xuid != null ? fg.xuid : "";
            }
            case "is_bedrock" -> Boolean.toString(
                session != null || (uuid != null && BedrockGatekeeper.isBedrockUuid(uuid)) || PurpurConfig.bedrockOnlyAllowBedrock
            );
            default -> null;
        };
    }

    public static int getDirectBedrockPing(@Nullable UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        Object session = findGeyserSession(uuid);
        if (session != null && sessionPing != null) {
            try {
                int rtt = (int) sessionPing.invoke(session);
                if (rtt > 0) {
                    return rtt;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    public static int getBedrockPing(@Nullable UUID uuid, @Nullable Player onlinePlayer) {
        Object session = uuid != null ? findGeyserSession(uuid) : null;
        return resolvePingMillis(session, onlinePlayer);
    }

    private static int resolvePingMillis(@Nullable Object session, @Nullable Player onlinePlayer) {
        if (session != null) {
            try {
                if (sessionPing != null) {
                    int rtt = (int) sessionPing.invoke(session);
                    if (rtt > 0) {
                        return rtt;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (onlinePlayer instanceof org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer) {
            var handle = craftPlayer.getHandle();
            if (handle.connection != null) {
                int javaPing = handle.connection.latency();
                if (javaPing > 0) {
                    return javaPing;
                }
            }
        }
        return 0;
    }

    private static String classifySessionDeviceId(@Nullable UUID uuid, Object session) {
        return classifyDeviceId(
            uuid,
            readPlatformCode(session),
            readInputCode(session),
            readDefaultInputCode(session),
            readDeviceModel(session)
        );
    }

    private static String classifySessionDeviceLabel(@Nullable UUID uuid, Object session) {
        return labelForDeviceId(classifySessionDeviceId(uuid, session));
    }

    public static String classifyDeviceLabel(
        @Nullable UUID uuid,
        @Nullable String rawPlatform,
        @Nullable String rawInput,
        @Nullable String rawDefaultInput,
        @Nullable String deviceModel
    ) {
        return labelForDeviceId(classifyDeviceId(uuid, rawPlatform, rawInput, rawDefaultInput, deviceModel));
    }

    public static String classifyDeviceId(
        @Nullable UUID uuid,
        @Nullable String rawPlatform,
        @Nullable String rawInput,
        @Nullable String rawDefaultInput,
        @Nullable String deviceModel
    ) {
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);
        String input = rawInput == null ? "UNKNOWN" : rawInput.trim().toUpperCase(Locale.ROOT);
        String defInput = rawDefaultInput == null ? "UNKNOWN" : rawDefaultInput.trim().toUpperCase(Locale.ROOT);
        String model = deviceModel == null ? "" : deviceModel.trim();

        boolean isKbmNow = "KEYBOARD_MOUSE".equals(input) || "MOUSE".equals(input);
        boolean isDefaultKbm = "KEYBOARD_MOUSE".equals(defInput) || "MOUSE".equals(defInput);
        boolean isMobileAndroid = switch (os) {
            case "GOOGLE", "ANDROID", "AMAZON", "FIREOS", "WINDOWS_PHONE" -> true;
            default -> false;
        };
        boolean isMobileIos = "IOS".equals(os) || "TVOS".equals(os) || "APPLE_TV".equals(os);

        if (isMobileAndroid || isMobileIos) {
            // Anti-spoof: Windows PC cheat clients spoofing DeviceOS=Android while keeping desktop DefaultInputMode=KBM and empty model
            if (isDefaultKbm && model.isEmpty()) {
                return "windows";
            }
            long now = System.currentTimeMillis();
            if (isKbmNow) {
                if (uuid != null) {
                    if (recentMobileKbmUntilMs.size() > 2048) {
                        recentMobileKbmUntilMs.clear();
                    }
                    recentMobileKbmUntilMs.put(uuid, now + PHONE_KBM_STICKY_MS);
                }
                return "phone_kbm";
            }
            if (uuid != null) {
                Long stickyUntil = recentMobileKbmUntilMs.get(uuid);
                if (stickyUntil != null) {
                    if (now < stickyUntil) {
                        return "phone_kbm";
                    }
                    recentMobileKbmUntilMs.remove(uuid);
                }
            }
            return isMobileIos ? "ios" : "android";
        }

        return switch (os) {
            case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX", "DEDICATED" -> "windows";
            case "XBOX", "XBOX_ONE", "XBOX_SERIES" -> "xbox";
            case "PS4", "PS5", "PLAYSTATION", "ORBIS" -> "ps4_5";
            case "NX", "SWITCH", "NINTENDO", "NINTENDO_SWITCH" -> "switch";
            default -> {
                if (isKbmNow || isDefaultKbm) {
                    yield "windows";
                }
                if ("CONTROLLER".equals(input) || "GAME_PAD".equals(input) || "GAMEPAD".equals(input)) {
                    yield "xbox";
                }
                yield "android";
            }
        };
    }

    public static String labelForDeviceId(String deviceId) {
        return switch (deviceId) {
            case "windows" -> PurpurConfig.bedrockPlaceholderDeviceWindows;
            case "android" -> PurpurConfig.bedrockPlaceholderDeviceAndroid;
            case "ios" -> PurpurConfig.bedrockPlaceholderDeviceIos;
            case "xbox" -> PurpurConfig.bedrockPlaceholderDeviceXbox;
            case "ps4_5" -> PurpurConfig.bedrockPlaceholderDevicePlaystation;
            case "phone_kbm" -> PurpurConfig.bedrockPlaceholderDevicePhoneKbm;
            case "switch" -> PurpurConfig.bedrockPlaceholderDeviceSwitch;
            default -> PurpurConfig.bedrockPlaceholderDeviceAndroid;
        };
    }

    public static String formatDevice(@Nullable String rawPlatform, @Nullable String rawInput) {
        return classifyDeviceLabel(null, rawPlatform, rawInput, "UNKNOWN", "device");
    }

    public static String formatInput(@Nullable String rawInput, @Nullable String rawPlatform) {
        String input = rawInput == null ? "UNKNOWN" : rawInput.trim().toUpperCase(Locale.ROOT);
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);

        return switch (input) {
            case "TOUCH" -> PurpurConfig.bedrockPlaceholderInputTouch;
            case "KEYBOARD_MOUSE", "MOUSE" -> PurpurConfig.bedrockPlaceholderInputKeyboardMouse;
            case "CONTROLLER", "GAME_PAD", "GAMEPAD" -> PurpurConfig.bedrockPlaceholderInputController;
            case "VR" -> "VR";
            default -> switch (os) {
                case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX" -> PurpurConfig.bedrockPlaceholderInputKeyboardMouse;
                case "PS4", "PS5", "PLAYSTATION", "ORBIS", "XBOX", "NX", "SWITCH", "TVOS", "GEARVR" -> PurpurConfig.bedrockPlaceholderInputController;
                default -> PurpurConfig.bedrockPlaceholderInputTouch;
            };
        };
    }

    private static String formatRawOsName(@Nullable String rawPlatform) {
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);
        return switch (os) {
            case "GOOGLE", "ANDROID", "AMAZON", "FIREOS", "WINDOWS_PHONE" -> PurpurConfig.bedrockPlaceholderDeviceAndroid;
            case "IOS", "TVOS", "APPLE_TV" -> PurpurConfig.bedrockPlaceholderDeviceIos;
            case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX", "DEDICATED" -> PurpurConfig.bedrockPlaceholderDeviceWindows;
            case "PS4", "PS5", "PLAYSTATION", "ORBIS" -> PurpurConfig.bedrockPlaceholderDevicePlaystation;
            case "XBOX", "XBOX_ONE", "XBOX_SERIES" -> PurpurConfig.bedrockPlaceholderDeviceXbox;
            case "NX", "SWITCH", "NINTENDO", "NINTENDO_SWITCH" -> PurpurConfig.bedrockPlaceholderDeviceSwitch;
            default -> "Bedrock";
        };
    }

    static @Nullable Object findGeyserSession(UUID uuid) {
        try {
            Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
            if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
                return null;
            }
            ClassLoader cl = geyserPlugin.getClass().getClassLoader();
            ensureGeyserHandles(cl);
            if (geyserGetInstance == null || geyserConnectionByUuid == null) {
                return null;
            }
            Object instance = geyserGetInstance.invoke(null);
            if (instance == null) {
                return null;
            }
            return geyserConnectionByUuid.invoke(instance, uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureGeyserHandles(ClassLoader cl) throws Exception {
        if (cachedGeyserLoader == cl && geyserConnectionByUuid != null) {
            return;
        }
        synchronized (BedrockPlaceholderHook.class) {
            if (cachedGeyserLoader == cl && geyserConnectionByUuid != null) {
                return;
            }
            Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl", true, cl);
            Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, cl);
            Class<?> clientDataClass = Class.forName("org.geysermc.geyser.session.auth.BedrockClientData", true, cl);
            Class<?> gameProtocolClass = Class.forName("org.geysermc.geyser.network.bedrock.GameProtocol", true, cl);

            geyserGetInstance = geyserImplClass.getMethod("getInstance");
            geyserConnectionByUuid = geyserImplClass.getMethod("connectionByUuid", UUID.class);
            sessionPlatform = sessionClass.getMethod("platform");
            sessionGetClientData = sessionClass.getMethod("getClientData");
            clientDataGetDeviceOs = clientDataClass.getMethod("getDeviceOs");
            clientDataGetDeviceModel = clientDataClass.getMethod("getDeviceModel");
            clientDataGetCurrentInputMode = clientDataClass.getMethod("getCurrentInputMode");
            clientDataGetDefaultInputMode = clientDataClass.getMethod("getDefaultInputMode");
            sessionInputMode = sessionClass.getMethod("inputMode");
            sessionVersion = sessionClass.getMethod("version");
            sessionPing = sessionClass.getMethod("ping");
            sessionProtocolVersion = sessionClass.getMethod("protocolVersion");
            sessionXuid = sessionClass.getMethod("xuid");
            sessionUiProfile = sessionClass.getMethod("uiProfile");
            gameProtocolGetBedrockCodec = gameProtocolClass.getMethod("getBedrockCodec", int.class);
            Class<?> codecClass = Class.forName("org.cloudburstmc.protocol.bedrock.codec.BedrockCodec", true, cl);
            codecGetMinecraftVersion = codecClass.getMethod("getMinecraftVersion");
            cachedGeyserLoader = cl;
        }
    }

    private static String readPlatformCode(Object session) {
        try {
            if (sessionGetClientData != null && clientDataGetDeviceOs != null) {
                Object clientData = sessionGetClientData.invoke(session);
                if (clientData != null) {
                    Object deviceOs = clientDataGetDeviceOs.invoke(clientData);
                    if (deviceOs instanceof Enum<?> e && !"UNKNOWN".equalsIgnoreCase(e.name())) {
                        return e.name();
                    }
                }
            }
            if (sessionPlatform != null) {
                Object p = sessionPlatform.invoke(session);
                if (p instanceof Enum<?> e) {
                    return e.name();
                }
            }
        } catch (Throwable ignored) {
        }
        return "UNKNOWN";
    }

    private static String readInputCode(Object session) {
        try {
            if (sessionInputMode != null) {
                Object m = sessionInputMode.invoke(session);
                if (m instanceof Enum<?> e && !"UNKNOWN".equalsIgnoreCase(e.name())) {
                    return e.name();
                }
            }
            if (sessionGetClientData != null && clientDataGetCurrentInputMode != null) {
                Object clientData = sessionGetClientData.invoke(session);
                if (clientData != null) {
                    Object m = clientDataGetCurrentInputMode.invoke(clientData);
                    if (m instanceof Enum<?> e) {
                        return e.name();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "UNKNOWN";
    }

    private static String readDefaultInputCode(Object session) {
        try {
            if (sessionGetClientData != null && clientDataGetDefaultInputMode != null) {
                Object clientData = sessionGetClientData.invoke(session);
                if (clientData != null) {
                    Object m = clientDataGetDefaultInputMode.invoke(clientData);
                    if (m instanceof Enum<?> e) {
                        return e.name();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "UNKNOWN";
    }

    private static String readDeviceModel(Object session) {
        try {
            if (sessionGetClientData != null && clientDataGetDeviceModel != null) {
                Object clientData = sessionGetClientData.invoke(session);
                if (clientData != null) {
                    Object m = clientDataGetDeviceModel.invoke(clientData);
                    if (m != null) {
                        return m.toString().trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String readClientVersion(Object session) {
        try {
            if (sessionVersion != null) {
                Object v = sessionVersion.invoke(session);
                if (v != null) {
                    String ver = v.toString().trim();
                    if (!ver.isEmpty() && !"unknown".equalsIgnoreCase(ver)) {
                        return ver;
                    }
                }
            }
            int proto = readProtocolVersion(session);
            if (proto > 0 && gameProtocolGetBedrockCodec != null && codecGetMinecraftVersion != null) {
                Object codec = gameProtocolGetBedrockCodec.invoke(null, proto);
                if (codec != null) {
                    Object mcVer = codecGetMinecraftVersion.invoke(codec);
                    if (mcVer != null && !mcVer.toString().isBlank()) {
                        return mcVer.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return Bukkit.getMinecraftVersion();
    }

    private static int readProtocolVersion(Object session) {
        try {
            if (sessionProtocolVersion != null) {
                return (int) sessionProtocolVersion.invoke(session);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String readUiProfile(Object session) {
        try {
            if (sessionUiProfile != null) {
                Object ui = sessionUiProfile.invoke(session);
                if (ui instanceof Enum<?> e) {
                    return "POCKET".equalsIgnoreCase(e.name()) ? "Pocket" : "Classic";
                }
            }
        } catch (Throwable ignored) {
        }
        return "Classic";
    }

    private static String readXuid(Object session) {
        try {
            if (sessionXuid != null) {
                Object x = sessionXuid.invoke(session);
                if (x != null) {
                    return x.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private record FloodgateSnapshot(String deviceOs, String inputMode, String version, String xuid) {
    }

    private static @Nullable FloodgateSnapshot readFloodgateSnapshot(UUID uuid) {
        try {
            Plugin fgPlugin = Bukkit.getPluginManager().getPlugin("floodgate");
            if (fgPlugin == null || !fgPlugin.isEnabled()) {
                return null;
            }
            ClassLoader cl = fgPlugin.getClass().getClassLoader();
            Class<?> fgApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, cl);
            Object api = fgApiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return null;
            }
            Object fgPlayer = api.getClass().getMethod("getPlayer", UUID.class).invoke(api, uuid);
            if (fgPlayer == null) {
                return null;
            }
            Object os = fgPlayer.getClass().getMethod("getDeviceOs").invoke(fgPlayer);
            Object input = fgPlayer.getClass().getMethod("getInputMode").invoke(fgPlayer);
            Object ver = fgPlayer.getClass().getMethod("getVersion").invoke(fgPlayer);
            Object xuid = fgPlayer.getClass().getMethod("getXuid").invoke(fgPlayer);
            return new FloodgateSnapshot(
                os instanceof Enum<?> e ? e.name() : String.valueOf(os),
                input instanceof Enum<?> e ? e.name() : String.valueOf(input),
                ver != null ? ver.toString() : "",
                xuid != null ? xuid.toString() : ""
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String runSelfTest() {
        byte[] bytecode = generateExpansionBytecode();
        if (bytecode == null || bytecode.length < 200) {
            throw new IllegalStateException("Generated PlaceholderExpansion bytecode invalid");
        }
        UUID mobileUser = UUID.randomUUID();
        if (!"Android".equals(formatDevice("GOOGLE", "TOUCH"))
            || !"IOS".equals(formatDevice("IOS", "TOUCH"))
            || !"Windows".equals(formatDevice("UWP", "KEYBOARD_MOUSE"))
            || !"Windows".equals(formatDevice("WIN32", "KEYBOARD_MOUSE"))
            || !"PS 4-5".equals(formatDevice("PS4", "CONTROLLER"))
            || !"Xbox".equals(formatDevice("XBOX", "CONTROLLER"))
            || !"Клавиатура + мышь".equals(classifyDeviceLabel(mobileUser, "GOOGLE", "KEYBOARD_MOUSE", "TOUCH", "SM-S918B"))
            || !"Клавиатура + мышь".equals(classifyDeviceLabel(mobileUser, "GOOGLE", "TOUCH", "TOUCH", "SM-S918B"))
            || !"Клавиатура + мышь".equals(classifyDeviceLabel(UUID.randomUUID(), "IOS", "KEYBOARD_MOUSE", "TOUCH", "iPhone15,2"))
            || !"Windows".equals(classifyDeviceLabel(UUID.randomUUID(), "GOOGLE", "KEYBOARD_MOUSE", "KEYBOARD_MOUSE", ""))) {
            throw new IllegalStateException("Device analyzer 6-category mapping mismatch");
        }
        recentMobileKbmUntilMs.remove(mobileUser);
        if (!"SAFE".equals(classifyPerformanceProfile("android", 35))
            || !"BALANCED".equals(classifyPerformanceProfile("xbox", 45))
            || !"BALANCED".equals(classifyPerformanceProfile("windows", 180))
            || !"HIGH".equals(classifyPerformanceProfile("windows", 35))) {
            throw new IllegalStateException("Adaptive performance profile mismatch");
        }
        if (!"Сенсор".equals(formatInput("TOUCH", "GOOGLE"))
            || !"Клавиатура + мышь".equals(formatInput("KEYBOARD_MOUSE", "UWP"))
            || !"Контроллер".equals(formatInput("CONTROLLER", "PS4"))) {
            throw new IllegalStateException("Input placeholder mapping mismatch");
        }
        return "PLACEHOLDER_PAPI_SELFTEST_OK bytecode=" + bytecode.length
            + "B devices=6(Windows|Android|IOS|Xbox|PS 4-5|Клавиатура + мышь) adaptiveProfiles=SAFE|BALANCED|HIGH";
    }

    private static final class PapiBridgeClassLoader extends ClassLoader {
        private final ClassLoader coreLoader;

        PapiBridgeClassLoader(ClassLoader papiLoader, ClassLoader coreLoader) {
            super(papiLoader);
            this.coreLoader = coreLoader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith("org.purpurmc.purpur.")) {
                return coreLoader.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }

        Class<?> defineExpansion(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
