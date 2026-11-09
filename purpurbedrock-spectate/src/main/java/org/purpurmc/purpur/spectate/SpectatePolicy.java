package org.purpurmc.purpur.spectate;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Pure decisions only. The Bukkit and protocol adapters must enforce them server-side. */
public final class SpectatePolicy {
    public static final double MAX_DISTANCE = 10.0;
    public enum Follow { STAY, FOLLOW, STOP }
    private SpectatePolicy() { }

    public record Position(UUID world, double x, double y, double z) {
        public Position { Objects.requireNonNull(world); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }

    /** If the target vanishes or coordinates are invalid, terminate rather than follow stale state. */
    public static Follow follow(Position observer, Position target) {
        if (observer == null || target == null || !observer.finite() || !target.finite()) return Follow.STOP;
        if (!observer.world().equals(target.world())) return Follow.FOLLOW;
        double dx = observer.x() - target.x(), dy = observer.y() - target.y(), dz = observer.z() - target.z();
        // Each axis is checked first to avoid overflow and guarantee bounded squared arithmetic.
        if (Math.abs(dx) > MAX_DISTANCE || Math.abs(dy) > MAX_DISTANCE || Math.abs(dz) > MAX_DISTANCE) return Follow.FOLLOW;
        return dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE ? Follow.FOLLOW : Follow.STAY;
    }

    /** Only the explicit exit command is allowed while observing; namespaces and arguments are not bypasses. */
    public static boolean isExitCommand(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '/') return false;
        if (raw.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\t')
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) return false;
        String input = raw.strip().toLowerCase(Locale.ROOT);
        return input.matches("/pbspec[ \t]+(off|выход)");
    }
}
