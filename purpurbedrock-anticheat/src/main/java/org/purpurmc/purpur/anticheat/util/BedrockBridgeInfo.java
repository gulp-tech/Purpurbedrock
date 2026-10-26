package org.purpurmc.purpur.anticheat.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart 6-category Bedrock Device and Live Phone-KBM Analyzer for PurpurBedrockAC 26.2 + VoidTab.
 *
 * Classifies every Bedrock player in real time into:
 * <ul>
 *   <li>{@code Windows} ({@code windows})</li>
 *   <li>{@code Android} ({@code android})</li>
 *   <li>{@code IOS} ({@code ios})</li>
 *   <li>{@code Xbox} ({@code xbox})</li>
 *   <li>{@code PS 4-5} ({@code ps4_5})</li>
 *   <li>{@code Клавиатура + мышь} ({@code phone_kbm} — OTG / Bluetooth keyboard + mouse on Android / iOS)</li>
 * </ul>
 */
public final class BedrockBridgeInfo {
    public enum InputCategory {
        TOUCH,
        KEYBOARD_MOUSE,
        CONTROLLER
    }

    private static final long PHONE_KBM_STICKY_MS = 45_000L;
    private static final Map<UUID, Long> recentMobileKbmUntilMs = new ConcurrentHashMap<>();

    private static volatile PurpurBedrockACPlugin activePlugin;
    private static volatile Method coreResolveOnlineMethod;
    private static volatile boolean coreChecked = false;

    // Direct Geyser-Spigot reflection handles (works on both old and new Purpurbedrock cores)
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

    private BedrockBridgeInfo() {
    }

    public static void bindPlugin(PurpurBedrockACPlugin plugin) {
        activePlugin = plugin;
        registerPlaceholdersIfPresent(plugin);
    }

    private static void initCore() {
        if (coreChecked) {
            return;
        }
        coreChecked = true;
        try {
            Class<?> hookClass = Class.forName("org.purpurmc.purpur.bedrock.BedrockPlaceholderHook");
            coreResolveOnlineMethod = hookClass.getMethod("resolveOnline", Player.class, String.class);
        } catch (Throwable ignored) {
            coreResolveOnlineMethod = null;
        }
    }

    private static String queryCore(Player player, String key) {
        initCore();
        if (coreResolveOnlineMethod != null && player != null) {
            try {
                Object val = coreResolveOnlineMethod.invoke(null, player, key);
                if (val != null) {
                    return val.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Returns one of the 6 canonical device names:
     * {@code Windows}, {@code Android}, {@code IOS}, {@code Xbox}, {@code PS 4-5}, {@code Клавиатура + мышь}.
     */
    public static String getDevice(Player player) {
        if (player == null) {
            return "—";
        }
        // If core already has the upgraded 6-category analyzer (exposes device_id), delegate to core
        String coreId = queryCore(player, "device_id");
        if (coreId != null && !coreId.isEmpty()) {
            String coreLabel = queryCore(player, "device");
            if (coreLabel != null && !coreLabel.isEmpty()) {
                return coreLabel;
            }
        }
        return labelForDeviceId(analyzeSessionDeviceId(player.getUniqueId()));
    }

    /**
     * Returns machine-readable category ID:
     * {@code windows}, {@code android}, {@code ios}, {@code xbox}, {@code ps4_5}, {@code phone_kbm}.
     */
    public static String getDeviceId(Player player) {
        if (player == null) {
            return "unknown";
        }
        String coreId = queryCore(player, "device_id");
        if (coreId != null && !coreId.isEmpty()) {
            return coreId;
        }
        return analyzeSessionDeviceId(player.getUniqueId());
    }

    public static boolean isPhoneKeyboardMouse(Player player) {
        return player != null && "phone_kbm".equals(getDeviceId(player));
    }

    public static String getDeviceModel(Player player) {
        if (player == null) {
            return "";
        }
        String coreModel = queryCore(player, "device_model");
        if (coreModel != null && !coreModel.isEmpty()) {
            return coreModel;
        }
        Object session = findGeyserSession(player.getUniqueId());
        return session != null ? readDeviceModel(session) : "";
    }

    public static String getInputDisplay(Player player) {
        if (player == null) {
            return "—";
        }
        Object session = findGeyserSession(player.getUniqueId());
        if (session != null) {
            return formatInput(readInputCode(session), readPlatformCode(session));
        }
        String val = queryCore(player, "input");
        return (val != null && !val.isEmpty()) ? val : "Сенсор";
    }

    public static InputCategory getInputCategory(Player player) {
        if (player != null && isPhoneKeyboardMouse(player)) {
            return InputCategory.KEYBOARD_MOUSE;
        }
        String raw = getInputDisplay(player);
        if (raw.contains("Клавиатура") || raw.equalsIgnoreCase("KEYBOARD_MOUSE") || raw.equalsIgnoreCase("MOUSE")) {
            return InputCategory.KEYBOARD_MOUSE;
        }
        if (raw.contains("Контроллер") || raw.equalsIgnoreCase("CONTROLLER") || raw.equalsIgnoreCase("GAME_PAD")) {
            return InputCategory.CONTROLLER;
        }
        return InputCategory.TOUCH;
    }

    public static String getVersion(Player player) {
        if (player == null) {
            return Bukkit.getMinecraftVersion();
        }
        String val = queryCore(player, "version");
        if (val != null && !val.isEmpty()) {
            return val;
        }
        Object session = findGeyserSession(player.getUniqueId());
        if (session != null && sessionVersion != null) {
            try {
                Object v = sessionVersion.invoke(session);
                if (v != null && !v.toString().isBlank() && !"unknown".equalsIgnoreCase(v.toString())) {
                    return v.toString().trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return Bukkit.getMinecraftVersion();
    }

    public static int getPing(Player player) {
        if (player == null) {
            return 0;
        }
        String val = queryCore(player, "ping");
        if (val != null) {
            try {
                int parsed = Integer.parseInt(val.trim());
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Object session = findGeyserSession(player.getUniqueId());
        if (session != null && sessionPing != null) {
            try {
                int rtt = (int) sessionPing.invoke(session);
                if (rtt > 0) {
                    return rtt;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            return Math.max(0, player.getPing());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static String analyzeSessionDeviceId(UUID uuid) {
        Object session = uuid != null ? findGeyserSession(uuid) : null;
        if (session == null) {
            return "android";
        }
        return classifyDeviceId(
            uuid,
            readPlatformCode(session),
            readInputCode(session),
            readDefaultInputCode(session),
            readDeviceModel(session)
        );
    }

    public static String classifyDeviceId(
        UUID uuid,
        String rawPlatform,
        String rawInput,
        String rawDefaultInput,
        String deviceModel
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
            case "windows" -> "Windows";
            case "android" -> "Android";
            case "ios" -> "IOS";
            case "xbox" -> "Xbox";
            case "ps4_5" -> "PS 4-5";
            case "phone_kbm" -> "Клавиатура + мышь";
            case "switch" -> "Switch";
            default -> "Android";
        };
    }

    private static String formatInput(String rawInput, String rawPlatform) {
        String input = rawInput == null ? "UNKNOWN" : rawInput.trim().toUpperCase(Locale.ROOT);
        String os = rawPlatform == null ? "UNKNOWN" : rawPlatform.trim().toUpperCase(Locale.ROOT);
        return switch (input) {
            case "TOUCH" -> "Сенсор";
            case "KEYBOARD_MOUSE", "MOUSE" -> "Клавиатура + мышь";
            case "CONTROLLER", "GAME_PAD", "GAMEPAD" -> "Контроллер";
            default -> switch (os) {
                case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX" -> "Клавиатура + мышь";
                case "PS4", "PS5", "PLAYSTATION", "ORBIS", "XBOX", "NX", "SWITCH" -> "Контроллер";
                default -> "Сенсор";
            };
        };
    }

    private static Object findGeyserSession(UUID uuid) {
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
            return instance != null ? geyserConnectionByUuid.invoke(instance, uuid) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureGeyserHandles(ClassLoader cl) throws Exception {
        if (cachedGeyserLoader == cl && geyserConnectionByUuid != null) {
            return;
        }
        synchronized (BedrockBridgeInfo.class) {
            if (cachedGeyserLoader == cl && geyserConnectionByUuid != null) {
                return;
            }
            Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl", true, cl);
            Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, cl);
            Class<?> clientDataClass = Class.forName("org.geysermc.geyser.session.auth.BedrockClientData", true, cl);

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
            cachedGeyserLoader = cl;
        }
    }

    private static String readPlatformCode(Object session) {
        try {
            if (sessionGetClientData != null && clientDataGetDeviceOs != null) {
                Object cd = sessionGetClientData.invoke(session);
                if (cd != null) {
                    Object os = clientDataGetDeviceOs.invoke(cd);
                    if (os instanceof Enum<?> e && !"UNKNOWN".equalsIgnoreCase(e.name())) {
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
                Object cd = sessionGetClientData.invoke(session);
                if (cd != null) {
                    Object m = clientDataGetCurrentInputMode.invoke(cd);
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
                Object cd = sessionGetClientData.invoke(session);
                if (cd != null) {
                    Object m = clientDataGetDefaultInputMode.invoke(cd);
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
                Object cd = sessionGetClientData.invoke(session);
                if (cd != null) {
                    Object m = clientDataGetDeviceModel.invoke(cd);
                    if (m != null) {
                        return m.toString().trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static void registerPlaceholdersIfPresent(PurpurBedrockACPlugin plugin) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null || !papi.isEnabled()) {
                return;
            }
            try {
                ClassLoader papiLoader = papi.getClass().getClassLoader();
                Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", true, papiLoader);
                BridgeClassLoader loader = new BridgeClassLoader(papiLoader, BedrockBridgeInfo.class.getClassLoader());

                byte[] acBytes = generatePapiExpansion("org.purpurmc.purpur.anticheat.papi.PurpurAcExpansion", "purpurac");
                Class<?> acClass = loader.define("org.purpurmc.purpur.anticheat.papi.PurpurAcExpansion", acBytes);
                acClass.getMethod("register").invoke(acClass.getDeclaredConstructor().newInstance());

                // Also upgrade %purpurbedrock_*% if running on an older core JAR without device_id
                initCore();
                boolean coreHasDeviceId = false;
                if (coreResolveOnlineMethod != null) {
                    try {
                        Class<?> hookClass = Class.forName("org.purpurmc.purpur.bedrock.BedrockPlaceholderHook");
                        hookClass.getMethod("analyzeDeviceId", Player.class);
                        coreHasDeviceId = true;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (!coreHasDeviceId) {
                    byte[] pbBytes = generatePapiExpansion("org.purpurmc.purpur.anticheat.papi.PurpurBedrockCompatExpansion", "purpurbedrock");
                    Class<?> pbClass = loader.define("org.purpurmc.purpur.anticheat.papi.PurpurBedrockCompatExpansion", pbBytes);
                    pbClass.getMethod("register").invoke(pbClass.getDeclaredConstructor().newInstance());
                }
            } catch (Throwable ignored) {
            }
        }, 20L);
    }

    public static String resolvePapiOffline(OfflinePlayer offline, String params) {
        if (params == null) {
            return null;
        }
        Player online = (offline != null && offline.isOnline()) ? offline.getPlayer() : null;
        return resolvePapiOnline(online, params);
    }

    public static String resolvePapiOnline(Player player, String rawParams) {
        if (rawParams == null) {
            return null;
        }
        String key = rawParams.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "device" -> player != null ? getDevice(player) : "—";
            case "device_id" -> player != null ? getDeviceId(player) : "unknown";
            case "is_phone_kbm", "phone_kbm" -> Boolean.toString(player != null && isPhoneKeyboardMouse(player));
            case "device_model", "model" -> player != null ? getDeviceModel(player) : "";
            case "input" -> player != null ? getInputDisplay(player) : "—";
            case "version" -> player != null ? getVersion(player) : Bukkit.getMinecraftVersion();
            case "ping" -> Integer.toString(player != null ? getPing(player) : 0);
            case "ping_colored" -> {
                int p = player != null ? getPing(player) : 0;
                String c = p <= 60 ? "§a" : (p <= 130 ? "§e" : "§c");
                yield c + p + " мс";
            }
            case "is_bedrock" -> "true";
            case "avg" -> {
                if (player != null && activePlugin != null) {
                    PlayerProfile prof = activePlugin.profile(player);
                    yield String.format(Locale.US, "%.2f", prof.avgScore);
                }
                yield "0.00";
            }
            case "vl" -> {
                if (player != null && activePlugin != null) {
                    PlayerProfile prof = activePlugin.profile(player);
                    yield String.format(Locale.US, "%.1f", prof.totalVl);
                }
                yield "0.0";
            }
            default -> player != null ? queryCore(player, key) : null;
        };
    }

    public static void verifySelfTest() {
        UUID u = UUID.randomUUID();
        if (!"Windows".equals(labelForDeviceId(classifyDeviceId(null, "UWP", "KEYBOARD_MOUSE", "KEYBOARD_MOUSE", "")))
            || !"Android".equals(labelForDeviceId(classifyDeviceId(null, "GOOGLE", "TOUCH", "TOUCH", "SM-S918B")))
            || !"IOS".equals(labelForDeviceId(classifyDeviceId(null, "IOS", "TOUCH", "TOUCH", "iPhone15,2")))
            || !"Xbox".equals(labelForDeviceId(classifyDeviceId(null, "XBOX", "CONTROLLER", "CONTROLLER", "Xbox")))
            || !"PS 4-5".equals(labelForDeviceId(classifyDeviceId(null, "PS4", "CONTROLLER", "CONTROLLER", "PlayStation")))
            || !"Клавиатура + мышь".equals(labelForDeviceId(classifyDeviceId(u, "GOOGLE", "KEYBOARD_MOUSE", "TOUCH", "SM-S918B")))
            || !"Клавиатура + мышь".equals(labelForDeviceId(classifyDeviceId(u, "GOOGLE", "TOUCH", "TOUCH", "SM-S918B")))
            || !"Клавиатура + мышь".equals(labelForDeviceId(classifyDeviceId(UUID.randomUUID(), "IOS", "KEYBOARD_MOUSE", "TOUCH", "iPad14,3")))) {
            throw new IllegalStateException("6-category Bedrock device analyzer selftest failed");
        }
        recentMobileKbmUntilMs.remove(u);
    }

    private static byte[] generatePapiExpansion(String implClassName, String identifier) {
        ClassDesc cdExpansion = ClassDesc.of("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        ClassDesc cdImpl = ClassDesc.of(implClassName);
        ClassDesc cdBridge = ClassDesc.of(BedrockBridgeInfo.class.getName());
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
                code.ldc(identifier);
                code.areturn();
            });
            cb.withMethodBody("getAuthor", stringNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.ldc("PurpurBedrockAC");
                code.areturn();
            });
            cb.withMethodBody("getVersion", stringNoArgDesc, ClassFile.ACC_PUBLIC, code -> {
                code.ldc("26.2.2");
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
                code.invokestatic(cdBridge, "resolvePapiOffline", onRequestDesc);
                code.areturn();
            });
            cb.withMethodBody("onPlaceholderRequest", onPlayerDesc, ClassFile.ACC_PUBLIC, code -> {
                code.aload(1);
                code.aload(2);
                code.invokestatic(cdBridge, "resolvePapiOnline", onPlayerDesc);
                code.areturn();
            });
        });
    }

    private static final class BridgeClassLoader extends ClassLoader {
        private final ClassLoader pluginLoader;

        BridgeClassLoader(ClassLoader papiLoader, ClassLoader pluginLoader) {
            super(papiLoader);
            this.pluginLoader = pluginLoader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith("org.purpurmc.purpur.anticheat.")) {
                return pluginLoader.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
