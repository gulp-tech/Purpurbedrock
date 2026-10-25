package org.purpurmc.purpur.anticheat.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.model.AdaptiveStage;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;
import org.purpurmc.purpur.anticheat.util.ColorUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PACCommand implements CommandExecutor, TabCompleter {
    private final PurpurBedrockACPlugin plugin;

    public PACCommand(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        ACConfig cfg = plugin.cfg();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "alerts", "alert" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cКоманда переключения алертов доступна игроку в игре."));
                    return true;
                }
                if (!player.hasPermission(cfg.permAlerts)) {
                    player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permAlerts + "&c!"));
                    return true;
                }
                boolean enabled = plugin.alerts().toggleAlerts(player);
                player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&7Алерты античита: " + (enabled ? "&aВКЛЮЧЕНЫ" : "&cВЫКЛЮЧЕНЫ")));
                return true;
            }
            case "holo", "hologram", "avg" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cГолограмма AVG включается модератором в игре."));
                    return true;
                }
                if (!player.hasPermission(cfg.permHologram)) {
                    player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permHologram + " &cна голограмму AVG!"));
                    return true;
                }
                boolean enabled = plugin.holograms().toggle(player);
                player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&7Голограмма AVG над игроками: " + (enabled ? "&aВКЛЮЧЕНА" : "&cВЫКЛЮЧЕНА")));
                return true;
            }
            case "menu", "gui" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cМеню открывается игроком в игре."));
                    return true;
                }
                if (!player.hasPermission(cfg.permMenu)) {
                    player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permMenu + "&c!"));
                    return true;
                }
                if (args.length >= 2) {
                    PlayerProfile target = plugin.findProfileByName(args[1]);
                    if (target == null) {
                        player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cИгрок &f" + args[1] + " &cне найден в кэше сессий."));
                        return true;
                    }
                    plugin.menus().openPlayerHistory(player, target, 0);
                } else {
                    plugin.menus().openOverview(player, 0);
                }
                return true;
            }
            case "history", "hist", "check" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cМеню истории открывается игроком в игре."));
                    return true;
                }
                if (!player.hasPermission(cfg.permMenu)) {
                    player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permMenu + "&c!"));
                    return true;
                }
                if (args.length < 2) {
                    plugin.menus().openOverview(player, 0);
                    return true;
                }
                PlayerProfile target = plugin.findProfileByName(args[1]);
                if (target == null) {
                    player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cИгрок &f" + args[1] + " &cне найден."));
                    return true;
                }
                plugin.menus().openPlayerHistory(player, target, 0);
                return true;
            }
            case "info", "profile" -> {
                if (!sender.hasPermission(cfg.permMenu) && !sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав!"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&eИспользование: &f/pac info <игрок>"));
                    return true;
                }
                PlayerProfile target = plugin.findProfileByName(args[1]);
                if (target == null) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cИгрок &f" + args[1] + " &cне найден."));
                    return true;
                }
                Player online = Bukkit.getPlayer(target.uuid);
                PlayerProfile.HistoryCounts counts = target.countHistoryByColor();
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== Досье PurpurBedrockAC: &f" + target.playerName + " &#A855F7&l==="));
                sender.sendMessage(ColorUtil.color(" &8• &7AVG: &e" + String.format(Locale.US, "%.2f", target.avgScore)
                    + " &8| &7Общий VL: &c" + String.format(Locale.US, "%.1f", target.totalVl)
                    + " &8| &7Режим ИИ: " + target.stage.badge()));
                sender.sendMessage(ColorUtil.color(" &8• &7История: &a🟢 " + counts.green()
                    + " &8| &6🟠 " + counts.orange() + " &8| &c🔴 " + counts.red()));
                if (online != null) {
                    sender.sendMessage(ColorUtil.color(" &8• &7Клиент: &f" + BedrockBridgeInfo.getDevice(online)
                        + " &8| &f" + BedrockBridgeInfo.getInputDisplay(online)
                        + " &8| &b" + BedrockBridgeInfo.getVersion(online)
                        + " &8| &a" + BedrockBridgeInfo.getPing(online) + " мс"));
                }
                return true;
            }
            case "reset", "clear" -> {
                if (!sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAdmin + "&c)!"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&eИспользование: &f/pac reset <игрок>"));
                    return true;
                }
                PlayerProfile target = plugin.findProfileByName(args[1]);
                if (target == null) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cИгрок &f" + args[1] + " &cне найден."));
                    return true;
                }
                target.resetViolations();
                sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&aИстория, AVG и VL игрока &f" + target.playerName + " &aочищены."));
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAdmin + "&c)!"));
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&aКонфигурация PurpurBedrockAC 26.2 успешно перезагружена!"));
                return true;
            }
            case "selftest" -> {
                if (!sender.hasPermission(cfg.permAdmin)) {
                    return true;
                }
                String res = plugin.runSelfTest();
                sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&a" + res));
                return true;
            }
            case "status" -> {
                if (!sender.hasPermission(cfg.permAlerts) && !sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав!"));
                    return true;
                }
                int sleeping = 0, waking = 0, awake = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    AdaptiveStage st = plugin.profile(p).stage;
                    if (st == AdaptiveStage.AWAKE) awake++;
                    else if (st == AdaptiveStage.WAKING) waking++;
                    else sleeping++;
                }
                double tps = Math.min(20.0, Bukkit.getTPS()[0]);
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== Статус PurpurBedrockAC 26.2 ==="));
                sender.sendMessage(ColorUtil.color(" &8• &7Режим адаптивного движка: " + plugin.engine().getGlobalStage().badge()
                    + " &8(&7пульс: &a" + plugin.engine().getLastPulseMicros() + " мкс&8)"));
                sender.sendMessage(ColorUtil.color(" &8• &7Профили онлайн: &aСон=" + sleeping
                    + " &8| &eПробуждение=" + waking + " &8| &cАктив=" + awake));
                sender.sendMessage(ColorUtil.color(" &8• &7Голограммы AVG: &f" + plugin.holograms().getActiveEntityCount()
                    + " сущн. &8(&7зрителей: &f" + plugin.holograms().getActiveViewerCount() + "&8)"));
                sender.sendMessage(ColorUtil.color(" &8• &7TPS сервера: &a" + String.format(Locale.US, "%.2f", tps)
                    + " &8| &7Проверок активно: &a13/13"));
                return true;
            }
            default -> {
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== PurpurBedrockAC 26.2 • Команды ==="));
                sender.sendMessage(ColorUtil.color(" &e/pac alerts &8— &7включить/выключить алерты (&f" + cfg.permAlerts + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac holo &8— &7включить/выключить голограмму AVG (&f" + cfg.permHologram + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac menu &8— &7открыть меню мониторинга игроков"));
                sender.sendMessage(ColorUtil.color(" &e/pac history <ник> &8— &7открыть историю игрока (🟢/🟠/🔴 HolyWorld)"));
                sender.sendMessage(ColorUtil.color(" &e/pac status &8— &7показать нагрузку и спящий/активный режим движка"));
                sender.sendMessage(ColorUtil.color(" &e/pac reset <ник> &8— &7сбросить VL и историю игрока"));
                sender.sendMessage(ColorUtil.color(" &e/pac reload &8— &7перезагрузить config.yml"));
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String alias,
        @NotNull String[] args
    ) {
        if (args.length == 1) {
            String pfx = args[0].toLowerCase(Locale.ROOT);
            List<String> subs = List.of("alerts", "holo", "menu", "history", "info", "status", "reset", "reload");
            return subs.stream().filter(s -> s.startsWith(pfx)).toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("history".equals(sub) || "hist".equals(sub) || "menu".equals(sub)
                || "info".equals(sub) || "reset".equals(sub)) {
                String pfx = args[1].toLowerCase(Locale.ROOT);
                List<String> names = new ArrayList<>();
                for (String n : plugin.cachedPlayerNames()) {
                    if (n.toLowerCase(Locale.ROOT).startsWith(pfx)) {
                        names.add(n);
                    }
                }
                return names;
            }
        }
        return Collections.emptyList();
    }
}
