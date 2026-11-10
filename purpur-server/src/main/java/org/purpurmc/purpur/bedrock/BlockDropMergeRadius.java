package org.purpurmc.purpur.bedrock;

/** Selects the normal item-merge radius, optionally extending it for block drops. */
public final class BlockDropMergeRadius {
    private static final double MAX_RADIUS = 8.0;

    private BlockDropMergeRadius() {
    }

    public static double resolve(double spigotRadius, double blockRadius, boolean blockItem) {
        // Preserve an explicitly disabled/invalid existing merge radius.
        if (!blockItem || !Double.isFinite(spigotRadius) || spigotRadius <= 0.0
            || !Double.isFinite(blockRadius) || blockRadius <= 0.0) {
            return spigotRadius;
        }
        return Math.max(spigotRadius, Math.min(blockRadius, MAX_RADIUS));
    }
}
