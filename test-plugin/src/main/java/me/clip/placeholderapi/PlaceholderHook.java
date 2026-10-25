package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public abstract class PlaceholderHook {
    public String onRequest(final OfflinePlayer player, final String params) {
        if (player != null && player.isOnline()) {
            return onPlaceholderRequest(player.getPlayer(), params);
        }
        return onPlaceholderRequest(null, params);
    }

    public String onPlaceholderRequest(final Player player, final String params) {
        return null;
    }
}
