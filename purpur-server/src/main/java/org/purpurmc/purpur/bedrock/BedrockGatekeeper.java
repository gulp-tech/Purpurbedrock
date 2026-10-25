package org.purpurmc.purpur.bedrock;

import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.purpurmc.purpur.PurpurConfig;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/**
 * Enforces Bedrock-only player access by rejecting direct Java Edition TCP clients
 * while admitting internal LocalChannel Bedrock sessions and Floodgate/Geyser API connections.
 */
public final class BedrockGatekeeper {
    private BedrockGatekeeper() {
    }

    public static boolean isBedrockConnection(Connection connection) {
        if (connection == null) {
            return false;
        }
        Channel ch = connection.channel;
        if (ch != null) {
            if (ch instanceof LocalChannel || ch instanceof LocalServerChannel) {
                return true;
            }
            String className = ch.getClass().getName();
            if (className.contains("geyser") || className.contains("LocalChannel") || className.contains("floodgate")) {
                return true;
            }
            if (ch.pipeline() != null && (ch.pipeline().get("floodgate_handler") != null
                || ch.pipeline().get("geyser") != null
                || ch.pipeline().get("geyser-compression-disabler") != null)) {
                return true;
            }
        }

        if (connection.hostname != null && connection.hostname.contains("Floodgate")) {
            return true;
        }

        SocketAddress remote = connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress) {
            InetAddress addr = inetSocketAddress.getAddress();
            if (addr != null) {
                String ip = addr.getHostAddress();
                if (PurpurConfig.bedrockAllowedBridgeIps.contains(ip)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isBedrockUuid(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (uuid.getMostSignificantBits() == 0L) {
            return true;
        }
        if (BedrockPlaceholderHook.findGeyserSession(uuid) != null) {
            return true;
        }
        try {
            org.bukkit.plugin.Plugin fgPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("floodgate");
            ClassLoader cl = fgPlugin != null ? fgPlugin.getClass().getClassLoader() : BedrockGatekeeper.class.getClassLoader();
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, cl);
            Object api = floodgateApiClass.getMethod("getInstance").invoke(null);
            if (api != null) {
                Method isFg = api.getClass().getMethod("isFloodgatePlayer", UUID.class);
                Object result = isFg.invoke(api, uuid);
                if (Boolean.TRUE.equals(result)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean shouldRejectJavaLogin(Connection connection) {
        if (!PurpurConfig.bedrockOnlyAllowBedrock) {
            return false;
        }
        return !isBedrockConnection(connection);
    }

    public static Component getJavaRejectComponent() {
        try {
            return PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(PurpurConfig.bedrockRejectJavaMessage));
        } catch (Throwable t) {
            return Component.literal("Сервер доступен только для Minecraft: Bedrock Edition! (UDP 19132)");
        }
    }
}
