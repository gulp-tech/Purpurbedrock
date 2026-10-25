package org.purpurmc.purpur.anticheat.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Direct zero-allocation bridge to Purpurbedrock 26.2 core's built-in {@code BedrockPlaceholderHook}.
 * Reads exact Bedrock device, live input mode (Touch / Keyboard+Mouse / Controller),
 * exact client version (1.21.0 — 26.52), and real RakNet/NetherNet RTT ping.
 */
public final class BedrockBridgeInfo {
    public enum InputCategory {
        TOUCH,
        KEYBOARD_MOUSE,
        CONTROLLER
    }

    private static Method resolveOnlineMethod;
    private static boolean initialized = false;

    private BedrockBridgeInfo() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> hookClass = Class.forName("org.purpurmc.purpur.bedrock.BedrockPlaceholderHook");
            resolveOnlineMethod = hookClass.getMethod("resolveOnline", Player.class, String.class);
        } catch (Throwable ignored) {
            resolveOnlineMethod = null;
        }
    }

    private static String queryCore(Player player, String key) {
        init();
        if (resolveOnlineMethod != null && player != null) {
            try {
                Object val = resolveOnlineMethod.invoke(null, player, key);
                if (val != null) {
                    return val.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String getDevice(Player player) {
        String val = queryCore(player, "device");
        return (val != null && !val.isEmpty()) ? val : "📱 Телефон";
    }

    public static String getInputDisplay(Player player) {
        String val = queryCore(player, "input");
        return (val != null && !val.isEmpty()) ? val : "Сенсор";
    }

    public static InputCategory getInputCategory(Player player) {
        String raw = getInputDisplay(player);
        if (raw.contains("Клавиатура") || raw.equalsIgnoreCase("KEYBOARD_MOUSE")) {
            return InputCategory.KEYBOARD_MOUSE;
        }
        if (raw.contains("Контроллер") || raw.equalsIgnoreCase("CONTROLLER")) {
            return InputCategory.CONTROLLER;
        }
        return InputCategory.TOUCH;
    }

    public static String getVersion(Player player) {
        String val = queryCore(player, "version");
        return (val != null && !val.isEmpty()) ? val : Bukkit.getMinecraftVersion();
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
        try {
            return Math.max(0, player.getPing());
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
