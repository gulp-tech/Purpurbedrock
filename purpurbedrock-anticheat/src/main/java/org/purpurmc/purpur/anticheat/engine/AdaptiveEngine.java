package org.purpurmc.purpur.anticheat.engine;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.model.AdaptiveStage;
import org.purpurmc.purpur.anticheat.model.HistoryEntry;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.model.SeverityColor;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;

import java.util.Locale;

/**
 * Adaptive Sleep -> Gradual Wake-Up controller.
 *
 * When nothing suspicious happens on the server, the engine stays in DORMANT / SLEEPING
 * mode (near-zero CPU). When anomalous patterns appear on a specific player, only that
 * player's profile smoothly transitions SLEEPING -> WAKING -> AWAKE without spiking MSPT.
 */
public final class AdaptiveEngine {
    private final PurpurBedrockACPlugin plugin;
    private BukkitTask pulseTask;
    private volatile AdaptiveStage globalStage = AdaptiveStage.DORMANT;
    private volatile long lastPulseMicros = 0L;

    public AdaptiveEngine(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        // Lightweight once-per-second (20 ticks) maintenance & cool-down pulse
        this.pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPulse, 20L, 20L);
    }

    public void stop() {
        if (this.pulseTask != null) {
            this.pulseTask.cancel();
            this.pulseTask = null;
        }
    }

    public AdaptiveStage getGlobalStage() {
        return globalStage;
    }

    public long getLastPulseMicros() {
        return lastPulseMicros;
    }

    /**
     * Decides whether a full movement check pass should run on this move packet
     * according to the player's current sleep/wake stage.
     */
    public boolean shouldSampleMove(PlayerProfile profile, ACConfig cfg) {
        long count = ++profile.movePacketCounter;
        return switch (profile.stage) {
            case AWAKE -> true;
            case WAKING -> (count % cfg.wakingSampleInterval) == 0L;
            case SLEEPING, DORMANT -> (count % cfg.sleepSampleInterval) == 0L;
        };
    }

    /**
     * Smoothly raises suspicion heat for this player when a pre-violation anomaly is seen,
     * waking up deeper checks gradually without sudden CPU spikes.
     */
    public void nudgeSuspicion(PlayerProfile profile, double multiplier) {
        ACConfig cfg = plugin.cfg();
        double delta = cfg.wakeUpStep * Math.clamp(multiplier, 0.15, 2.5);
        profile.suspicionHeat = Math.min(1.0, profile.suspicionHeat + delta);
        refreshStage(profile, cfg, System.currentTimeMillis());
    }

    public void refreshStage(PlayerProfile profile, ACConfig cfg, long nowMs) {
        if (nowMs - profile.lastActiveMoveMs > 10_000L && profile.suspicionHeat < 0.05) {
            profile.stage = AdaptiveStage.DORMANT;
        } else if (profile.suspicionHeat >= cfg.awakeThreshold) {
            profile.stage = AdaptiveStage.AWAKE;
        } else if (profile.suspicionHeat >= cfg.wakingThreshold) {
            profile.stage = AdaptiveStage.WAKING;
        } else {
            profile.stage = AdaptiveStage.SLEEPING;
        }
    }

    private void tickPulse() {
        var online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) {
            globalStage = AdaptiveStage.DORMANT;
            lastPulseMicros = 0L;
            return;
        }

        long startNs = System.nanoTime();
        ACConfig cfg = plugin.cfg();
        long nowMs = System.currentTimeMillis();
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        AdaptiveStage highest = AdaptiveStage.DORMANT;
        long cleanIntervalMs = cfg.cleanHistorySampleSeconds * 1000L;

        for (Player player : online) {
            PlayerProfile profile = plugin.profile(player);
            if (profile.suspicionHeat > 0.0) {
                profile.suspicionHeat = Math.max(0.0, profile.suspicionHeat - cfg.coolDownPerSecond);
            }
            if (profile.avgScore > 0.0) {
                profile.avgScore = Math.max(0.0, profile.avgScore * 0.985 - 0.01);
                if (profile.avgScore < 0.02) {
                    profile.avgScore = 0.0;
                }
            }
            refreshStage(profile, cfg, nowMs);
            if (profile.stage.ordinal() > highest.ordinal()) {
                highest = profile.stage;
            }

            // Record periodic GREEN clean baseline entries for active players so HolyWorld history shows clean segments
            if (nowMs - profile.lastActiveMoveMs <= 8_000L
                && profile.suspicionHeat < cfg.wakingThreshold
                && nowMs - profile.lastCleanSampleMs >= cleanIntervalMs) {
                profile.lastCleanSampleMs = nowMs;
                int ping = BedrockBridgeInfo.getPing(player);
                String details = String.format(
                    Locale.US,
                    "Чистая сессия • CPS=%.0f • Reach=%.2f",
                    profile.currentCps,
                    profile.lastMeasuredReach
                );
                profile.pushHistory(
                    new HistoryEntry(
                        nowMs,
                        SeverityColor.GREEN,
                        "Чистая игра",
                        details,
                        profile.totalVl,
                        profile.avgScore,
                        ping,
                        tps,
                        BedrockBridgeInfo.getDevice(player),
                        BedrockBridgeInfo.getInputDisplay(player),
                        "Норма"
                    ),
                    cfg.maxHistoryEntries
                );
            }
        }

        globalStage = highest;
        lastPulseMicros = (System.nanoTime() - startNs) / 1_000L;
    }
}
