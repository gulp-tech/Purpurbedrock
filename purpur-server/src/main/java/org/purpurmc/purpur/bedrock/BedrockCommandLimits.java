package org.purpurmc.purpur.bedrock;

/** Resolve a bounded client command-list limit without overriding custom values. */
public final class BedrockCommandLimits {
    private BedrockCommandLimits() {}

    public static int advertised(int configured, boolean upgradeLegacyDefault) {
        if (upgradeLegacyDefault && configured == 96) configured = 512;
        return Math.max(32, Math.min(1024, configured));
    }
}
