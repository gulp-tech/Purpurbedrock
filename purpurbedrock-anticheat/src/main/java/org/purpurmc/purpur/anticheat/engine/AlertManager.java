package org.purpurmc.purpur.anticheat.engine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.model.HistoryEntry;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.model.SeverityColor;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;
import org.purpurmc.purpur.anticheat.util.ColorUtil;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AlertManager {
    private final PurpurBedrockACPlugin plugin;
    private final Set<UUID> staffAlertsEnabled = ConcurrentHashMap.newKeySet();

    public AlertManager(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public void onPlayerJoin(Player player) {
        ACConfig cfg = plugin.cfg();
        if (cfg.alertsAutoEnableOnJoin && player.hasPermission(cfg.permAlerts)) {
            staffAlertsEnabled.add(player.getUniqueId());
        }
    }

    public void onPlayerQuit(UUID uuid) {
        staffAlertsEnabled.remove(uuid);
    }

    public boolean hasAlertsEnabled(Player player) {
        return player.hasPermission(plugin.cfg().permAlerts) && staffAlertsEnabled.contains(player.getUniqueId());
    }

    public boolean toggleAlerts(Player player) {
        UUID id = player.getUniqueId();
        if (staffAlertsEnabled.contains(id)) {
            staffAlertsEnabled.remove(id);
            return false;
        }
        staffAlertsEnabled.add(id);
        return true;
    }

    public SeverityColor classifySeverity(double checkVl, ACConfig cfg) {
        if (checkVl >= cfg.redVlThreshold) {
            return SeverityColor.RED;
        }
        if (checkVl >= cfg.orangeVlThreshold) {
            return SeverityColor.ORANGE;
        }
        return SeverityColor.GREEN;
    }

    public String avgColorCode(double avg, ACConfig cfg) {
        if (avg <= cfg.hologramGreenMaxAvg) {
            return "&a";
        }
        if (avg <= cfg.hologramOrangeMaxAvg) {
            return "&6";
        }
        return "&c";
    }

    public void handleViolation(
        Player player,
        PlayerProfile profile,
        String checkKey,
        String displayName,
        String details,
        double vlAdded,
        String actionTaken
    ) {
        ACConfig cfg = plugin.cfg();
        ACConfig.CheckSettings settings = cfg.check(checkKey);
        double checkVl = profile.incrementVl(checkKey, vlAdded);
        long nowMs = System.currentTimeMillis();
        int ping = BedrockBridgeInfo.getPing(player);
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        String device = BedrockBridgeInfo.getDevice(player);
        String input = BedrockBridgeInfo.getInputDisplay(player);
        String version = BedrockBridgeInfo.getVersion(player);
        SeverityColor severity = classifySeverity(checkVl, cfg);

        profile.pushHistory(
            new HistoryEntry(
                nowMs,
                severity,
                displayName,
                details,
                checkVl,
                profile.avgScore,
                ping,
                tps,
                device,
                input,
                actionTaken
            ),
            cfg.maxHistoryEntries
        );

        // Rate-limit chat broadcast per suspect so chat never floods
        if (nowMs - profile.lastAlertSentMs >= cfg.alertMinIntervalMs) {
            profile.lastAlertSentMs = nowMs;
            sendAlertBroadcast(player, profile, displayName, details, checkVl, severity, ping, tps, device, input, version, cfg);
        }

        // Execute configured max-vl punishment commands if configured
        if (settings.maxVl > 0 && checkVl >= settings.maxVl && !settings.punishCommands.isEmpty()) {
            profile.setBuffer(checkKey, 0.0);
            for (String rawCmd : settings.punishCommands) {
                String cmd = applyPlaceholders(rawCmd, player.getName(), displayName, details, checkVl, profile.avgScore,
                    severity, ping, tps, device, input, version, profile.stage.title(), cfg);
                if (cmd.startsWith("/")) {
                    cmd = cmd.substring(1);
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    private void sendAlertBroadcast(
        Player suspect,
        PlayerProfile profile,
        String checkDisplay,
        String details,
        double checkVl,
        SeverityColor severity,
        int ping,
        double tps,
        String device,
        String input,
        String version,
        ACConfig cfg
    ) {
        String formattedLine = applyPlaceholders(
            cfg.alertFormat,
            suspect.getName(),
            checkDisplay,
            details,
            checkVl,
            profile.avgScore,
            severity,
            ping,
            tps,
            device,
            input,
            version,
            profile.stage.title(),
            cfg
        );
        Component msg = ColorUtil.color(formattedLine);

        if (!cfg.alertHover.isEmpty()) {
            StringBuilder hoverRaw = new StringBuilder();
            for (int i = 0; i < cfg.alertHover.size(); i++) {
                if (i > 0) {
                    hoverRaw.append('\n');
                }
                hoverRaw.append(applyPlaceholders(
                    cfg.alertHover.get(i),
                    suspect.getName(),
                    checkDisplay,
                    details,
                    checkVl,
                    profile.avgScore,
                    severity,
                    ping,
                    tps,
                    device,
                    input,
                    version,
                    profile.stage.title(),
                    cfg
                ));
            }
            msg = msg.hoverEvent(HoverEvent.showText(ColorUtil.color(hoverRaw.toString())));
        }

        if (cfg.alertClickCommand != null && !cfg.alertClickCommand.isBlank()) {
            String clickCmd = cfg.alertClickCommand.replace("{player}", suspect.getName());
            msg = msg.clickEvent(ClickEvent.runCommand(clickCmd));
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(cfg.permAlerts) && staffAlertsEnabled.contains(online.getUniqueId())) {
                online.sendMessage(msg);
                if (cfg.alertPlaySound) {
                    float pitch = severity == SeverityColor.RED ? 0.85f : (severity == SeverityColor.ORANGE ? 1.25f : 1.6f);
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.45f, pitch);
                }
            }
        }

        if (cfg.consoleAlerts) {
            Bukkit.getConsoleSender().sendMessage(msg);
        }
    }

    private String applyPlaceholders(
        String template,
        String player,
        String check,
        String details,
        double vl,
        double avg,
        SeverityColor severity,
        int ping,
        double tps,
        String device,
        String input,
        String version,
        String stage,
        ACConfig cfg
    ) {
        return template
            .replace("{prefix}", cfg.alertPrefix)
            .replace("{player}", player)
            .replace("{check}", check)
            .replace("{details}", details)
            .replace("{vl}", String.format(Locale.US, "%.1f", vl))
            .replace("{avg}", String.format(Locale.US, "%.1f", avg))
            .replace("{severity_color}", severity.colorCode())
            .replace("{avg_color}", avgColorCode(avg, cfg))
            .replace("{ping}", Integer.toString(ping))
            .replace("{tps}", String.format(Locale.US, "%.1f", tps))
            .replace("{device}", device)
            .replace("{input}", input)
            .replace("{version}", version)
            .replace("{stage}", stage);
    }
}
