package org.purpurmc.purpur.bedrock;

import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import io.netty.util.AttributeKey;
import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.purpurmc.purpur.PurpurConfig;

/**
 * Enforces Bedrock-only server entry while allowing Geyser's in-JVM LocalChannel
 * (and optional Floodgate proxy/plugin connections) to pass through seamlessly.
 */
public final class BedrockGatekeeper {
    private static final String FLOODGATE_ATTR_NAME = "floodgate-player";

    private BedrockGatekeeper() {
    }

    public static boolean isLocalGeyserConnection(final Connection connection) {
        return connection != null && connection.channel instanceof LocalChannel;
    }

    public static boolean hasFloodgateAttribute(final Channel channel) {
        if (channel == null || !AttributeKey.exists(FLOODGATE_ATTR_NAME)) {
            return false;
        }
        AttributeKey<Object> key = AttributeKey.valueOf(FLOODGATE_ATTR_NAME);
        return channel.hasAttr(key) && channel.attr(key).get() != null;
    }

    public static boolean isBedrockConnection(final Connection connection) {
        if (connection == null || connection.channel == null) {
            return false;
        }
        if (connection.channel instanceof LocalChannel) {
            return true;
        }
        return PurpurConfig.bedrockAllowExternalFloodgate && hasFloodgateAttribute(connection.channel);
    }

    public static boolean isBedrockPlayer(final ServerPlayer player) {
        return player != null && player.connection != null && isBedrockConnection(player.connection.connection);
    }

    public static boolean shouldRejectJavaLogin(final Connection connection) {
        return PurpurConfig.bedrockOnlyAllowBedrock && !isBedrockConnection(connection);
    }

    public static Component getJavaRejectComponent() {
        String raw = PurpurConfig.bedrockJavaKickMessage;
        if (raw == null || raw.isBlank()) {
            raw = "<red><bold>Заход с Minecraft: Java Edition отключён!</bold>\n<gray>Этот сервер доступен только для игроков <aqua>Minecraft: Bedrock Edition</aqua>.";
        }
        return PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(raw));
    }
}
