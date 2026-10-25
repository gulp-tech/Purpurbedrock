package me.clip.placeholderapi.expansion;

import me.clip.placeholderapi.PlaceholderHook;
import org.bukkit.Bukkit;

public abstract class PlaceholderExpansion extends PlaceholderHook {
    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public boolean persist() {
        return false;
    }

    public boolean canRegister() {
        return true;
    }

    public boolean register() {
        if (!canRegister()) {
            return false;
        }
        String isBedrock = onRequest(null, "is_bedrock");
        String ping = onPlaceholderRequest(null, "ping");
        Bukkit.getLogger().info(
            "PAPI_EXPANSION_LIVE_REGISTERED_OK id=" + getIdentifier()
                + " persist=" + persist()
                + " ver=" + getVersion()
                + " isBedrock=" + isBedrock
                + " ping=" + ping
        );
        return "purpurbedrock".equals(getIdentifier())
            && persist()
            && "true".equals(isBedrock)
            && ping != null;
    }
}
