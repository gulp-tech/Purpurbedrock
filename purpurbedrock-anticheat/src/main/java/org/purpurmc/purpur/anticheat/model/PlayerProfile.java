package org.purpurmc.purpur.anticheat.model;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Zero-GC per-player state container using pre-allocated primitive ring buffers.
 * Calibrated for Geyser 26.2 + Bedrock 1.21.0..26.52 physics and latency compensation.
 */
public final class PlayerProfile {
    private static final int POS_RING_SIZE = 18;
    private static final int CLICK_RING_SIZE = 24;
    private static final int BREAK_RING_SIZE = 16;
    private static final int STEAL_RING_SIZE = 12;

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
    private final Map<String, Double> checkBuffers = new HashMap<>(24);
    private final Map<String, Double> checkVls = new HashMap<>(24);
    // Temporal evidence gate: isolated translator/latency spikes never become moderator complaints.
    private final Map<String, Long> evidenceWindowStarts = new HashMap<>(24);
    private final Map<String, Long> evidenceLastSamples = new HashMap<>(24);
    private final Map<String, Integer> evidenceCounts = new HashMap<>(24);
    private final Map<String, Long> lastConfirmedViolations = new HashMap<>(24);
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

    // Pre-allocated combat click timing ring buffer (CPS + constant-interval macro check)
    private final long[] clickTimesMs = new long[CLICK_RING_SIZE];
    private int clickWriteIdx = 0;
    private int clickCount = 0;
    public double currentCps = 0.0;

    // Pre-allocated block-break ring buffer (for Nuker rate check)
    private final long[] breakTimesMs = new long[BREAK_RING_SIZE];
    private int breakWriteIdx = 0;
    private int breakCount = 0;

    // Pre-allocated container-loot ring buffer (for ChestStealer check)
    private final long[] stealTimesMs = new long[STEAL_RING_SIZE];
    private int stealWriteIdx = 0;
    private int stealCount = 0;
    public int lastStealRawSlot = -999;

    // Movement & Physics state
    public Location lastSafeLocation;
    public long lastSetbackMs = 0L;
    public double lastDeltaXZ = 0.0;
    public double lastDeltaY = 0.0;
    public boolean wasNearGround = true;
    public int airTicks = 0;
    public int hoverTicks = 0;
    public int wallClimbTicks = 0;
    public int groundSpoofTicks = 0;
    public int liquidSurfaceTicks = 0;
    public int usingItemSprintTicks = 0;
    public int invMoveTicks = 0;
    public int iceOrSpeedBoostTicks = 0;
    public int ceilingHitTicks = 0;
    public double trackedFallDistance = 0.0;

    // Grim-style Token Bucket Timer & Blink tracking (100% immune to mobile Wi-Fi stalls)
    public long lastTimerPacketMs = 0L;
    public double timerBalanceMs = 0.0;
    public double lastMoveX = 0.0;
    public double lastMoveY = 0.0;
    public double lastMoveZ = 0.0;
    public boolean hasMoveAnchor = false;

    // Combat & MultiAura tracking
    public UUID lastAttackTargetUuid = null;
    public long lastAttackTimeMs = 0L;
    public float lastAttackYaw = 0.0f;
    public long lastMoveSeqAtAttack = -1L;
    public double lastMeasuredReach = 0.0;

    // Aim / SnapRotations state
    public float lastYaw = 0.0f;
    public float lastPitch = 0.0f;
    public float prevMoveYawDelta = 0.0f;
    public boolean hasRotationSample = false;
    public boolean snapAttackPending = false;
    public float preSnapYaw = 0.0f;
    public float snapAttackYaw = 0.0f;
    public int snapMovesSinceAttack = 0;
    public int zeroPitchAimStreak = 0;

    // AutoTotem & FastBow & ElytraFly state
    public long lastTotemPopMs = 0L;
    public long bowDrawStartMs = 0L;
    public long fireworkBoostUntilMs = 0L;
    public int elytraHoverTicks = 0;
    public int elytraAscendTicks = 0;

    // Scaffold & Tower state
    public int towerStreak = 0;
    public long lastTowerPlaceMs = 0L;
    public int lastTowerBlockY = Integer.MIN_VALUE;

    // FastBreak / InstaBreak state
    public long mineStartMs = 0L;
    public int mineX = 0;
    public int mineY = 0;
    public int mineZ = 0;
    public boolean miningTracked = false;

    // Container state
    public long worldContainerOpenedMs = 0L;
    public boolean worldContainerOpen = false;

    // Exemptions & Physics Grace Windows (eliminates Elytra, WindCharge, Slime, Boat, Piston false flags)
    public long exemptUntilMs = 0L;
    public long lastTeleportMs = 0L;
    public long lastVelocityMs = 0L;
    public long glideOrRiptideGraceUntilMs = 0L;
    public long slimeOrPistonGraceUntilMs = 0L;
    public long explosionGraceUntilMs = 0L;
    public long vehicleGraceUntilMs = 0L;

    // Velocity (AntiKB) tracking
    public double expectedVelocityY = 0.0;
    public double expectedVelocityXZ = 0.0;
    public boolean velocityCheckPending = false;
    public double maxObservedPostVelY = 0.0;
    public double maxObservedPostVelXZ = 0.0;
    public long lastAlertSentMs = 0L;

    public PlayerProfile(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
        long now = System.currentTimeMillis();
        this.joinTimeMs = now;
        this.exemptUntilMs = now + 4500L; // 4.5s initial join chunk/spawn grace
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
     * {@code [minTimestampMs, now]} + live BoundingBox, eliminating moving-target latency false flags.
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

    /**
     * Ray-traces the attacker's look vector against BOTH the live target AABB and all
     * historical AABBs inside {@code [minTimestampMs, now]} so moving targets at 80-300ms
     * ping never false-flag HitBox on PC/Controller.
     */
    public boolean intersectsCompensatedRay(
        Vector eyeVec,
        Vector direction,
        BoundingBox currentBox,
        double expandXZ,
        double expandY,
        double maxRayDist,
        long minTimestampMs
    ) {
        BoundingBox scratch = new BoundingBox(
            currentBox.getMinX() - expandXZ, currentBox.getMinY() - expandY, currentBox.getMinZ() - expandXZ,
            currentBox.getMaxX() + expandXZ, currentBox.getMaxY() + expandY, currentBox.getMaxZ() + expandXZ
        );
        RayTraceResult liveHit = scratch.rayTrace(eyeVec, direction, maxRayDist);
        if (liveHit != null) {
            return true;
        }
        for (int n = 0; n < posCount; n++) {
            if (posTimeMs[n] >= minTimestampMs) {
                scratch.resize(
                    posMinX[n] - expandXZ, posMinY[n] - expandY, posMinZ[n] - expandXZ,
                    posMaxX[n] + expandXZ, posMaxY[n] + expandY, posMaxZ[n] + expandXZ
                );
                if (scratch.rayTrace(eyeVec, direction, maxRayDist) != null) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Grim-style Token Bucket Timer balance update.
     * Grants negative credit up to {@code -maxLagCreditMs} when Wi-Fi stalls so burst flushes
     * of queued packets return to {@code <= 0ms} instead of false-flagging!
     * Returns current positive timer excess in milliseconds.
     */
    public double advanceTimerBucket(long nowMs, double maxLagCreditMs) {
        if (lastTimerPacketMs == 0L) {
            lastTimerPacketMs = nowMs;
            timerBalanceMs = 0.0;
            return 0.0;
        }
        long elapsed = Math.max(0L, nowMs - lastTimerPacketMs);
        lastTimerPacketMs = nowMs;
        // Subtract real elapsed wall-clock time, add 50ms for 1 client tick
        timerBalanceMs += (50.0 - elapsed);
        if (timerBalanceMs < -maxLagCreditMs) {
            timerBalanceMs = -maxLagCreditMs;
        }
        return timerBalanceMs;
    }

    public void resetTimerBucket(long nowMs) {
        lastTimerPacketMs = nowMs;
        timerBalanceMs = 0.0;
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
            if (dt <= 0L || dt > 240L) {
                return 999.0;
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

    public int recordNonInstantBreakAndCountPerSec(long nowMs) {
        breakTimesMs[breakWriteIdx] = nowMs;
        breakWriteIdx = (breakWriteIdx + 1) % BREAK_RING_SIZE;
        if (breakCount < BREAK_RING_SIZE) {
            breakCount++;
        }
        int count = 0;
        long cutoff = nowMs - 1000L;
        for (int i = 0; i < breakCount; i++) {
            if (breakTimesMs[i] >= cutoff) {
                count++;
            }
        }
        return count;
    }

    public void resetStealRing() {
        stealCount = 0;
        stealWriteIdx = 0;
        lastStealRawSlot = -999;
    }

    /**
     * Records a distinct container slot loot click and returns average interval (ms) across last 6 loot clicks,
     * or {@code 999.0} if not enough clicks yet.
     */
    public double recordStealClickAndAvgIntervalMs(long nowMs) {
        stealTimesMs[stealWriteIdx] = nowMs;
        stealWriteIdx = (stealWriteIdx + 1) % STEAL_RING_SIZE;
        if (stealCount < STEAL_RING_SIZE) {
            stealCount++;
        }
        if (stealCount < 7) {
            return 999.0;
        }
        int intervals = 6;
        long newest = stealTimesMs[(stealWriteIdx - 1 + STEAL_RING_SIZE) % STEAL_RING_SIZE];
        long oldest = stealTimesMs[(stealWriteIdx - 1 - intervals + STEAL_RING_SIZE * 2) % STEAL_RING_SIZE];
        long span = newest - oldest;
        if (span < 0L || span > 1500L) {
            return 999.0;
        }
        return span / (double) intervals;
    }

    public boolean hasActiveBoostGrace(long nowMs) {
        return nowMs < glideOrRiptideGraceUntilMs
            || nowMs < slimeOrPistonGraceUntilMs
            || nowMs < explosionGraceUntilMs
            || nowMs < vehicleGraceUntilMs;
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

    /**
     * Confirms that detector evidence is sustained rather than one isolated network/translation spike.
     * Samples arriving too close together count as one physical incident. Once confirmed, continued
     * evidence is rate-limited per check so staff history and VL cannot be flooded every movement tick.
     */
    public synchronized boolean confirmViolationEvidence(
        String checkKey,
        long nowMs,
        int requiredSamples,
        long minSeparationMs,
        long windowMs,
        long confirmedCooldownMs
    ) {
        long lastConfirmed = lastConfirmedViolations.getOrDefault(checkKey, 0L);
        if (lastConfirmed > 0L) {
            if (nowMs - lastConfirmed < confirmedCooldownMs) {
                return false;
            }
            // A new report needs a new time-separated evidence sequence after the cooldown too.
            lastConfirmedViolations.remove(checkKey);
        }

        if (requiredSamples <= 1) {
            lastConfirmedViolations.put(checkKey, nowMs);
            return true;
        }

        long start = evidenceWindowStarts.getOrDefault(checkKey, 0L);
        long lastSample = evidenceLastSamples.getOrDefault(checkKey, 0L);
        if (start == 0L || nowMs - start > windowMs) {
            evidenceWindowStarts.put(checkKey, nowMs);
            evidenceLastSamples.put(checkKey, nowMs);
            evidenceCounts.put(checkKey, 1);
            return false;
        }
        if (nowMs - lastSample < minSeparationMs) {
            return false;
        }

        int count = evidenceCounts.getOrDefault(checkKey, 1) + 1;
        evidenceLastSamples.put(checkKey, nowMs);
        evidenceCounts.put(checkKey, count);
        if (count < requiredSamples) {
            return false;
        }

        evidenceWindowStarts.remove(checkKey);
        evidenceLastSamples.remove(checkKey);
        evidenceCounts.remove(checkKey);
        lastConfirmedViolations.put(checkKey, nowMs);
        return true;
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

    public int countViolatedChecks() {
        int c = 0;
        for (double v : checkVls.values()) {
            if (v >= 1.0) {
                c++;
            }
        }
        return c;
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
        evidenceWindowStarts.clear();
        evidenceLastSamples.clear();
        evidenceCounts.clear();
        lastConfirmedViolations.clear();
        history.clear();
        totalVl = 0.0;
        avgScore = 0.0;
        suspicionHeat = 0.0;
        stage = AdaptiveStage.SLEEPING;
    }

    public record HistoryCounts(int green, int orange, int red) {
    }
}
