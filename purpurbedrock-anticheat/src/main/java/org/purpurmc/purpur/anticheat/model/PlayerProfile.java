package org.purpurmc.purpur.anticheat.model;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Zero-GC per-player state container using pre-allocated primitive ring buffers.
 */
public final class PlayerProfile {
    private static final int POS_RING_SIZE = 14;
    private static final int CLICK_RING_SIZE = 24;

    public final UUID uuid;
    public volatile String playerName;
    public final long joinTimeMs;

    // Adaptive Sleep -> Wake state
    public double suspicionHeat = 0.0;
    public AdaptiveStage stage = AdaptiveStage.SLEEPING;
    public long movePacketCounter = 0L;
    public long lastActiveMoveMs = 0L;
    public long lastCleanSampleMs = 0L;

    // AVG & VL metrics
    public double avgScore = 0.0;
    public double totalVl = 0.0;
    private final Map<String, Double> checkBuffers = new HashMap<>(16);
    private final Map<String, Double> checkVls = new HashMap<>(16);
    private final Deque<HistoryEntry> history = new ArrayDeque<>(80);

    // Pre-allocated position history ring buffer (for ping-compensated 3D AABB Reach & HitBox)
    private final double[] posMinX = new double[POS_RING_SIZE];
    private final double[] posMinY = new double[POS_RING_SIZE];
    private final double[] posMinZ = new double[POS_RING_SIZE];
    private final double[] posMaxX = new double[POS_RING_SIZE];
    private final double[] posMaxY = new double[POS_RING_SIZE];
    private final double[] posMaxZ = new double[POS_RING_SIZE];
    private final long[] posTimeMs = new long[POS_RING_SIZE];
    private int posWriteIdx = 0;
    private int posCount = 0;

    // Pre-allocated click timing ring buffer (for CPS & variance AutoClicker check)
    private final long[] clickTimesMs = new long[CLICK_RING_SIZE];
    private int clickWriteIdx = 0;
    private int clickCount = 0;
    public double currentCps = 0.0;

    // Movement & Physics state
    public Location lastSafeLocation;
    public double lastDeltaXZ = 0.0;
    public double lastDeltaY = 0.0;
    public int airTicks = 0;
    public int hoverTicks = 0;
    public int liquidSurfaceTicks = 0;
    public int usingItemSprintTicks = 0;
    public int iceOrSpeedBoostTicks = 0;
    public int ceilingHitTicks = 0;

    // Timer token bucket
    public long lastTimerMoveMs = 0L;
    public double timerAllowanceMs = 0.0;
    public int timerSampleMoves = 0;
    public long timerWindowStartMs = 0L;

    // Combat & MultiAura tracking
    public UUID lastAttackTargetUuid = null;
    public long lastAttackTimeMs = 0L;
    public double lastMeasuredReach = 0.0;

    // Exemptions & Knockback Velocity tracking
    public long exemptUntilMs = 0L;
    public long lastTeleportMs = 0L;
    public long lastVelocityMs = 0L;
    public double expectedVelocityY = 0.0;
    public boolean velocityCheckPending = false;
    public double maxObservedPostVelY = 0.0;
    public long lastAlertSentMs = 0L;

    public PlayerProfile(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
        long now = System.currentTimeMillis();
        this.joinTimeMs = now;
        this.exemptUntilMs = now + 4000L; // 4s initial join chunk/spawn grace
        this.lastCleanSampleMs = now;
    }

    public void recordBoundingBox(BoundingBox box, long nowMs) {
        int i = posWriteIdx;
        posMinX[i] = box.getMinX();
        posMinY[i] = box.getMinY();
        posMinZ[i] = box.getMinZ();
        posMaxX[i] = box.getMaxX();
        posMaxY[i] = box.getMaxY();
        posMaxZ[i] = box.getMaxZ();
        posTimeMs[i] = nowMs;
        posWriteIdx = (i + 1) % POS_RING_SIZE;
        if (posCount < POS_RING_SIZE) {
            posCount++;
        }
    }

    /**
     * Computes minimum 3D eye-to-AABB distance across the ping-compensated window
     * {@code [now - rttMs - 130ms, now]} + live BoundingBox, eliminating latency false flags.
     */
    public double computeMinCompensatedDistance(
        double eyeX,
        double eyeY,
        double eyeZ,
        BoundingBox currentBox,
        double expand,
        long minTimestampMs
    ) {
        double bestSq = distanceSqToBox(
            eyeX, eyeY, eyeZ,
            currentBox.getMinX() - expand, currentBox.getMinY() - expand, currentBox.getMinZ() - expand,
            currentBox.getMaxX() + expand, currentBox.getMaxY() + expand, currentBox.getMaxZ() + expand
        );
        for (int n = 0; n < posCount; n++) {
            if (posTimeMs[n] >= minTimestampMs) {
                double dSq = distanceSqToBox(
                    eyeX, eyeY, eyeZ,
                    posMinX[n] - expand, posMinY[n] - expand, posMinZ[n] - expand,
                    posMaxX[n] + expand, posMaxY[n] + expand, posMaxZ[n] + expand
                );
                if (dSq < bestSq) {
                    bestSq = dSq;
                }
            }
        }
        return Math.sqrt(bestSq);
    }

    private static double distanceSqToBox(
        double px, double py, double pz,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
    ) {
        double dx = Math.max(0.0, Math.max(minX - px, px - maxX));
        double dy = Math.max(0.0, Math.max(minY - py, py - maxY));
        double dz = Math.max(0.0, Math.max(minZ - pz, pz - maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    public int recordClickAndCountCps(long nowMs) {
        clickTimesMs[clickWriteIdx] = nowMs;
        clickWriteIdx = (clickWriteIdx + 1) % CLICK_RING_SIZE;
        if (clickCount < CLICK_RING_SIZE) {
            clickCount++;
        }
        int inLastSecond = 0;
        long cutoff = nowMs - 1000L;
        for (int i = 0; i < clickCount; i++) {
            if (clickTimesMs[i] >= cutoff) {
                inLastSecond++;
            }
        }
        this.currentCps = inLastSecond;
        return inLastSecond;
    }

    /**
     * Computes standard deviation (ms) of the last 16 inter-click intervals
     * to catch constant-delay autoclickers/macros without false-flagging human jitter.
     */
    public double computeClickIntervalStdDevMs() {
        if (clickCount < 16) {
            return 999.0;
        }
        int samples = 15;
        double sum = 0.0;
        double[] intervals = new double[samples];
        for (int k = 0; k < samples; k++) {
            int currIdx = (clickWriteIdx - 1 - k + CLICK_RING_SIZE * 2) % CLICK_RING_SIZE;
            int prevIdx = (clickWriteIdx - 2 - k + CLICK_RING_SIZE * 2) % CLICK_RING_SIZE;
            long dt = clickTimesMs[currIdx] - clickTimesMs[prevIdx];
            if (dt <= 0L || dt > 250L) {
                return 999.0; // Natural pause in clicking, not a constant-speed macro
            }
            intervals[k] = dt;
            sum += dt;
        }
        double mean = sum / samples;
        double sqDiff = 0.0;
        for (int k = 0; k < samples; k++) {
            double d = intervals[k] - mean;
            sqDiff += d * d;
        }
        return Math.sqrt(sqDiff / samples);
    }

    public double addBuffer(String checkKey, double amount) {
        double next = checkBuffers.getOrDefault(checkKey, 0.0) + amount;
        checkBuffers.put(checkKey, next);
        return next;
    }

    public double decayBuffer(String checkKey, double decay) {
        double cur = checkBuffers.getOrDefault(checkKey, 0.0);
        if (cur <= 0.0) {
            return 0.0;
        }
        double next = Math.max(0.0, cur - decay);
        checkBuffers.put(checkKey, next);
        return next;
    }

    public void setBuffer(String checkKey, double val) {
        checkBuffers.put(checkKey, Math.max(0.0, val));
    }

    public double incrementVl(String checkKey, double amount) {
        double next = checkVls.getOrDefault(checkKey, 0.0) + amount;
        checkVls.put(checkKey, next);
        totalVl += amount;
        avgScore = Math.min(10.0, avgScore + amount * 0.85);
        return next;
    }

    public double getCheckVl(String checkKey) {
        return checkVls.getOrDefault(checkKey, 0.0);
    }

    public synchronized void pushHistory(HistoryEntry entry, int maxEntries) {
        history.addFirst(entry);
        while (history.size() > maxEntries) {
            history.removeLast();
        }
    }

    public synchronized List<HistoryEntry> snapshotHistory() {
        return new ArrayList<>(history);
    }

    public synchronized HistoryCounts countHistoryByColor() {
        int g = 0, o = 0, r = 0;
        for (HistoryEntry e : history) {
            switch (e.severity()) {
                case GREEN -> g++;
                case ORANGE -> o++;
                case RED -> r++;
            }
        }
        return new HistoryCounts(g, o, r);
    }

    public synchronized void resetViolations() {
        checkBuffers.clear();
        checkVls.clear();
        history.clear();
        totalVl = 0.0;
        avgScore = 0.0;
        suspicionHeat = 0.0;
        stage = AdaptiveStage.SLEEPING;
    }

    public record HistoryCounts(int green, int orange, int red) {
    }
}
