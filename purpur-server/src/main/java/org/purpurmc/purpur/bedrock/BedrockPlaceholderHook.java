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
import java.util.UUID;
import java.util.logging.Level;

/**
 * Built-in PlaceholderAPI provider directly inside the Purpurbedrock 26.2 server core.
 *
 * Automatically registers the {@code %purpurbedrock_*%} expansion whenever PlaceholderAPI
 * is installed (with {@code persist() = true} so {@code /papi reload} preserves it),
 * with zero external bridge plugins required:
 * <ul>
 *   <li>{@code %purpurbedrock_device%} — {@code 📱 Телефон}, {@code 🖥 ПК}, {@code 🎮 Геймпад}, {@code PlayStation}, {@code Xbox}, {@code Switch}</li>
 *   <li>{@code %purpurbedrock_input%} — {@code Сенсор}, {@code Клавиатура+Мышь}, {@code Контроллер}</li>
 *   <li>{@code %purpurbedrock_version%} — exact Bedrock client version (e.g. {@code 1.21.50} or {@code 26.52})</li>
 *   <li>{@code %purpurbedrock_ping%} — real RakNet / NetherNet WebRTC RTT ping in ms</li>
 * </ul>
 */
public final class BedrockPlaceholderHook {
    private static final String EXPANSION_CLASS_NAME = "org.purpurmc.purpur.bedrock.papi.PurpurBedrockPapiExpansion";
    private static volatile boolean registered = false;

    // Cached Geyser reflection handles (resolved lazily once against Geyser-Spigot's ClassLoader)
    private static volatile ClassLoader cachedGeyserLoader;
    private static volatile Method geyserGetInstance;
    private static volatile Method geyserConnectionByUuid;
    private static volatile Method sessionPlatform;
    private static volatile Method sessionGetClientData;
    private static volatile Method clientDataGetDeviceOs;
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
            // Verify PlaceholderExpansion exists in this PlaceholderAPI plugin
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
                        + "(device, input, version, ping)!"
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

    /**
     * Generates bytecode for a subclass of {@code me.clip.placeholderapi.expansion.PlaceholderExpansion}
     * using JDK's standard {@link ClassFile} API (zero external compile-time dependencies).
     */
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
                    yield formatDevice(readPlatformCode(session), readInputCode(session));
                }
                FloodgateSnapshot fg = uuid != null ? readFloodgateSnapshot(uuid) : null;
                if (fg != null) {
                    yield formatDevice(fg.deviceOs, fg.inputMode);
                }
                yield onlinePlayer != null ? PurpurConfig.bedrockPlaceholderDevicePhone : "—";
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

    public static String formatDevice(@Nullable String rawPlatform, @Nullable String rawInput) {
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);
        String input = rawInput == null ? "UNKNOWN" : rawInput.trim().toUpperCase(Locale.ROOT);

        return switch (os) {
            case "GOOGLE", "ANDROID", "IOS", "AMAZON", "FIREOS", "WINDOWS_PHONE" -> PurpurConfig.bedrockPlaceholderDevicePhone;
            case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX" -> PurpurConfig.bedrockPlaceholderDevicePc;
            case "PS4", "PS5", "PLAYSTATION" -> PurpurConfig.bedrockPlaceholderDevicePlaystation;
            case "XBOX", "XBOX_ONE", "XBOX_SERIES" -> PurpurConfig.bedrockPlaceholderDeviceXbox;
            case "NX", "SWITCH", "NINTENDO", "NINTENDO_SWITCH" -> PurpurConfig.bedrockPlaceholderDeviceSwitch;
            case "TVOS", "APPLE_TV", "GEARVR", "HOLOLENS" -> PurpurConfig.bedrockPlaceholderDeviceGamepad;
            default -> switch (input) {
                case "CONTROLLER" -> PurpurConfig.bedrockPlaceholderDeviceGamepad;
                case "KEYBOARD_MOUSE", "MOUSE" -> PurpurConfig.bedrockPlaceholderDevicePc;
                default -> PurpurConfig.bedrockPlaceholderDevicePhone;
            };
        };
    }

    public static String formatInput(@Nullable String rawInput, @Nullable String rawPlatform) {
        String input = rawInput == null ? "UNKNOWN" : rawInput.trim().toUpperCase(Locale.ROOT);
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);

        return switch (input) {
            case "TOUCH" -> PurpurConfig.bedrockPlaceholderInputTouch;
            case "KEYBOARD_MOUSE", "MOUSE" -> PurpurConfig.bedrockPlaceholderInputKeyboardMouse;
            case "CONTROLLER" -> PurpurConfig.bedrockPlaceholderInputController;
            case "VR" -> "VR";
            default -> switch (os) {
                case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX" -> PurpurConfig.bedrockPlaceholderInputKeyboardMouse;
                case "PS4", "PS5", "PLAYSTATION", "XBOX", "NX", "SWITCH", "TVOS", "GEARVR" -> PurpurConfig.bedrockPlaceholderInputController;
                default -> PurpurConfig.bedrockPlaceholderInputTouch;
            };
        };
    }

    private static String formatRawOsName(@Nullable String rawPlatform) {
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);
        return switch (os) {
            case "GOOGLE", "ANDROID" -> "Android";
            case "IOS" -> "iOS";
            case "AMAZON" -> "Amazon Fire";
            case "UWP", "WIN32", "WINDOWS" -> "Windows";
            case "OSX", "MACOS" -> "macOS";
            case "LINUX" -> "Linux";
            case "PS4", "PLAYSTATION" -> "PlayStation";
            case "XBOX" -> "Xbox";
            case "NX", "SWITCH" -> "Switch";
            case "TVOS" -> "Apple TV";
            case "GEARVR" -> "Gear VR";
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
                    if (deviceOs instanceof Enum<?> e) {
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
                if (m instanceof Enum<?> e) {
                    return e.name();
                }
            }
        } catch (Throwable ignored) {
        }
        return "UNKNOWN";
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
        if (!"📱 Телефон".equals(formatDevice("GOOGLE", "TOUCH"))
            || !"📱 Телефон".equals(formatDevice("IOS", "TOUCH"))
            || !"🖥 ПК".equals(formatDevice("UWP", "KEYBOARD_MOUSE"))
            || !"🖥 ПК".equals(formatDevice("WIN32", "KEYBOARD_MOUSE"))
            || !"🎮 Геймпад".equals(formatDevice("UNKNOWN", "CONTROLLER"))
            || !"PlayStation".equals(formatDevice("PS4", "CONTROLLER"))
            || !"Xbox".equals(formatDevice("XBOX", "CONTROLLER"))
            || !"Switch".equals(formatDevice("NX", "CONTROLLER"))) {
            throw new IllegalStateException("Device placeholder mapping mismatch");
        }
        if (!"Сенсор".equals(formatInput("TOUCH", "GOOGLE"))
            || !"Клавиатура+Мышь".equals(formatInput("KEYBOARD_MOUSE", "UWP"))
            || !"Контроллер".equals(formatInput("CONTROLLER", "PS4"))) {
            throw new IllegalStateException("Input placeholder mapping mismatch");
        }
        return "PLACEHOLDER_PAPI_SELFTEST_OK bytecode=" + bytecode.length + "B (%purpurbedrock_device|input|version|ping%)";
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
