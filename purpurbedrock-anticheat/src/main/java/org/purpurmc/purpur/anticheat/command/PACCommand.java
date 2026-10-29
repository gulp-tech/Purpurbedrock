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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PACCommand implements CommandExecutor, TabCompleter {
    private final PurpurBedrockACPlugin plugin;

    public PACCommand(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean hasAnyStaffPerm(CommandSender sender, ACConfig cfg) {
        return sender.hasPermission(cfg.permAdmin)
            || sender.hasPermission(cfg.permAlerts)
            || sender.hasPermission(cfg.permHologram)
            || sender.hasPermission(cfg.permMenu);
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
                if (!player.hasPermission(cfg.permAlerts) && !player.hasPermission(cfg.permAdmin)) {
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
                if (!player.hasPermission(cfg.permHologram) && !player.hasPermission(cfg.permAdmin)) {
                    player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permHologram + " &cна голограмму AVG!"));
                    return true;
                }
                boolean enabled = plugin.holograms().toggle(player);
                player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&7Голограмма AVG над игроками (&fвысота "
                    + String.format(Locale.US, "%.2f", cfg.hologramHeightOffset) + "&7): "
                    + (enabled ? "&aВКЛЮЧЕНА (20 Гц без задержки)" : "&cВЫКЛЮЧЕНА")));
                return true;
            }
            case "holoheight", "height" -> {
                if (!sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAdmin + "&c)!"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&7Текущая высота голограммы AVG: &e"
                        + String.format(Locale.US, "%.2f", cfg.hologramHeightOffset)
                        + " &8| &7Изменить: &f/pac holoheight <1.8..5.0>"));
                    return true;
                }
                try {
                    double val = Double.parseDouble(args[1].replace(',', '.'));
                    cfg.setHologramHeightOffset(plugin, val);
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&aВысота голограммы AVG установлена на &f"
                        + String.format(Locale.US, "%.2f", cfg.hologramHeightOffset) + " &a(сохранено в config.yml)!"));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУкажите число, например: &f/pac holoheight 2.85"));
                }
                return true;
            }
            case "menu", "gui" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cМеню открывается игроком в игре."));
                    return true;
                }
                if (!player.hasPermission(cfg.permMenu) && !player.hasPermission(cfg.permAdmin)) {
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
            case "checks", "modules", "settings" -> {
                if (sender instanceof Player player) {
                    if (!player.hasPermission(cfg.permAdmin) && !player.hasPermission(cfg.permMenu)) {
                        player.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAdmin + "&c)!"));
                        return true;
                    }
                    plugin.menus().openChecksConfig(player);
                    return true;
                }
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== PurpurBedrockAC • Модули ("
                    + cfg.countEnabledChecks() + "/" + ACConfig.ALL_CHECKS.size() + ") ==="));
                for (ACConfig.CheckMeta m : ACConfig.ALL_CHECKS) {
                    ACConfig.CheckSettings s = cfg.check(m.key());
                    sender.sendMessage(ColorUtil.color(" &8• " + (s.enabled ? "&a✔ " : "&c✖ ")
                        + "&f" + m.key() + " &8— &7" + m.title()));
                }
                return true;
            }
            case "toggle" -> {
                if (!sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAdmin + "&c)!"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&eИспользование: &f/pac toggle <модуль>"));
                    return true;
                }
                ACConfig.CheckMeta meta = cfg.findMeta(args[1]);
                if (meta == null) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cМодуль &f" + args[1] + " &cне найден! Список: &f/pac checks"));
                    return true;
                }
                boolean nowOn = cfg.toggleCheckEnabled(plugin, meta.key());
                sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&7Проверка &f" + meta.title() + "&7: "
                    + (nowOn ? "&aВКЛЮЧЕНА" : "&cВЫКЛЮЧЕНА") + " &8(сохранено в config.yml)"));
                return true;
            }
            case "autopunish", "autokick" -> {
                if (!sender.hasPermission(cfg.permAdmin)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAdmin + "&c)!"));
                    return true;
                }
                boolean nowOn = cfg.toggleAutoPunish(plugin);
                sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&7Авто-наказания при высоком VL: "
                    + (nowOn ? "&eВКЛЮЧЕНЫ (с защитой пинга/TPS)" : "&aВЫКЛЮЧЕНЫ (только сетбэк и отмена читов без случайных киков)")));
                return true;
            }
            case "history", "hist", "check" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cМеню истории открывается игроком в игре."));
                    return true;
                }
                if (!player.hasPermission(cfg.permMenu) && !player.hasPermission(cfg.permAdmin)) {
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
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permMenu + "&c)!"));
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
            case "top" -> {
                if (!hasAnyStaffPerm(sender, cfg)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAlerts + "&c)!"));
                    return true;
                }
                List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                online.sort(Comparator.comparingDouble((Player p) -> plugin.profile(p).avgScore * 10.0 + plugin.profile(p).totalVl).reversed());
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== Топ подозрительных игроков онлайн ==="));
                if (online.isEmpty()) {
                    sender.sendMessage(ColorUtil.color(" &8• &7Нет игроков онлайн."));
                    return true;
                }
                int limit = Math.min(10, online.size());
                for (int i = 0; i < limit; i++) {
                    Player p = online.get(i);
                    PlayerProfile prof = plugin.profile(p);
                    sender.sendMessage(ColorUtil.color(" &e#" + (i + 1) + " &f" + p.getName()
                        + " &8— &7AVG: &e" + String.format(Locale.US, "%.2f", prof.avgScore)
                        + " &8| &7VL: &c" + String.format(Locale.US, "%.1f", prof.totalVl)
                        + " &8| &b" + BedrockBridgeInfo.getDevice(p)
                        + " &8| &a" + BedrockBridgeInfo.getPing(p) + " мс"));
                }
                return true;
            }
            case "devices", "device" -> {
                if (!hasAnyStaffPerm(sender, cfg)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав!"));
                    return true;
                }
                if (args.length >= 2) {
                    Player p = Bukkit.getPlayerExact(args[1]);
                    if (p == null) {
                        sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cИгрок &f" + args[1] + " &cсейчас не онлайн."));
                        return true;
                    }
                    String model = BedrockBridgeInfo.getDeviceModel(p);
                    sender.sendMessage(ColorUtil.color("&#A855F7&l=== Анализатор устройства: &f" + p.getName() + " &#A855F7&l==="));
                    sender.sendMessage(ColorUtil.color(" &8• &7Категория: &f" + BedrockBridgeInfo.getDevice(p)
                        + " &8(&7id: &e" + BedrockBridgeInfo.getDeviceId(p) + "&8)"));
                    sender.sendMessage(ColorUtil.color(" &8• &7Ввод: &f" + BedrockBridgeInfo.getInputDisplay(p)
                        + " &8| &7КМ с телефона: " + (BedrockBridgeInfo.isPhoneKeyboardMouse(p) ? "&cДА (OTG/Bluetooth)" : "&aНЕТ")));
                    sender.sendMessage(ColorUtil.color(" &8• &7Модель: &f" + (model.isEmpty() ? "—" : model)
                        + " &8| &7Версия: &b" + BedrockBridgeInfo.getVersion(p)
                        + " &8| &7Пинг: &a" + BedrockBridgeInfo.getPing(p) + " мс"));
                    return true;
                }
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== Устройства онлайн (Windows | Android | IOS | Xbox | PS 4-5 | Клавиатура + мышь) ==="));
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    sender.sendMessage(ColorUtil.color(" &8• &7Нет игроков онлайн."));
                    return true;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String dev = BedrockBridgeInfo.getDevice(p);
                    boolean phoneKbm = BedrockBridgeInfo.isPhoneKeyboardMouse(p);
                    sender.sendMessage(ColorUtil.color(" &8• &f" + p.getName() + " &8-> "
                        + (phoneKbm ? "&c&l" : "&b") + dev
                        + " &8| &7" + BedrockBridgeInfo.getVersion(p)
                        + " &8| &a" + BedrockBridgeInfo.getPing(p) + " мс"));
                }
                return true;
            }
            case "perms", "permissions" -> {
                if (!hasAnyStaffPerm(sender, cfg)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав!"));
                    return true;
                }
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== Права PurpurBedrockAC 26.2 ==="));
                sender.sendMessage(ColorUtil.color(" &e" + cfg.permAlerts + " &8— &7/pac alerts, /pac status, /pac top, /pac devices"));
                sender.sendMessage(ColorUtil.color(" &e" + cfg.permHologram + " &8— &7/pac holo (голограмма AVG над игроками)"));
                sender.sendMessage(ColorUtil.color(" &e" + cfg.permMenu + " &8— &7/pac menu, /pac history <ник>, /pac info <ник>"));
                sender.sendMessage(ColorUtil.color(" &e" + cfg.permAdmin + " &8— &7все команды + /pac checks, toggle, autopunish, holoheight, reset, reload"));
                sender.sendMessage(ColorUtil.color(" &e" + cfg.permBypass + " &8— &7обход проверок (по умолч. выключен в config.yml)"));
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
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав (&f" + cfg.permAlerts + "&c)!"));
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
                    + " сущн. &8(&7зрителей: &f" + plugin.holograms().getActiveViewerCount()
                    + "&8, &7высота: &f" + String.format(Locale.US, "%.2f", cfg.hologramHeightOffset) + "&8)"));
                sender.sendMessage(ColorUtil.color(" &8• &7TPS сервера: &a" + String.format(Locale.US, "%.2f", tps)
                    + " &8| &7Проверок активно: &a" + cfg.countEnabledChecks() + "/" + ACConfig.ALL_CHECKS.size()
                    + " &8| &7Авто-кик: " + (cfg.autoPunishEnabled ? "&eВКЛ" : "&aБЕЗОПАСНО (Сетбэк)")));
                return true;
            }
            default -> {
                if (!hasAnyStaffPerm(sender, cfg)) {
                    sender.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cНедостаточно прав! Нужна привилегия &f" + cfg.permAdmin
                        + " &cили &f" + cfg.permMenu + "&c."));
                    return true;
                }
                sender.sendMessage(ColorUtil.color("&#A855F7&l=== PurpurBedrockAC 26.2.7 • Команды (25 проверок) ==="));
                sender.sendMessage(ColorUtil.color(" &e/pac alerts &8— &7вкл/выкл алерты (&f" + cfg.permAlerts + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac holo &8— &7вкл/выкл быструю голограмму AVG (&f" + cfg.permHologram + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac holoheight <высота> &8— &7настроить высоту голограммы (&f" + cfg.permAdmin + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac menu &8— &7меню мониторинга игроков (&f" + cfg.permMenu + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac history <ник> &8— &7история игрока 🟢/🟠/🔴 HolyWorld (&f" + cfg.permMenu + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac info <ник> &8— &7текстовое досье игрока (&f" + cfg.permMenu + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac top &8— &7топ подозрительных игроков по AVG/VL (&f" + cfg.permAlerts + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac devices [ник] &8— &7анализатор 6 устройств + КМ с телефона"));
                sender.sendMessage(ColorUtil.color(" &e/pac checks &8— &7GUI настройки всех 25 модулей (&f" + cfg.permAdmin + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac toggle <модуль> &8— &7вкл/выкл модуль на лету (&f" + cfg.permAdmin + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac autopunish &8— &7вкл/выкл авто-кик (&f" + cfg.permAdmin + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac perms &8— &7показать все права на команды"));
                sender.sendMessage(ColorUtil.color(" &e/pac status &8— &7статус движка и нагрузки (&f" + cfg.permAlerts + "&7)"));
                sender.sendMessage(ColorUtil.color(" &e/pac reset <ник> &8| &e/pac reload &8— &7сброс VL / перезагрузка (&f" + cfg.permAdmin + "&7)"));
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
        ACConfig cfg = plugin.cfg();
        if (!hasAnyStaffPerm(sender, cfg)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String pfx = args[0].toLowerCase(Locale.ROOT);
            List<String> subs = List.of(
                "alerts", "holo", "holoheight", "menu", "checks", "toggle",
                "autopunish", "history", "info", "top", "devices", "perms", "status", "reset", "reload"
            );
            return subs.stream().filter(s -> s.startsWith(pfx)).toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String pfx = args[1].toLowerCase(Locale.ROOT);
            if ("toggle".equals(sub)) {
                return ACConfig.ALL_CHECKS.stream().map(ACConfig.CheckMeta::key).filter(k -> k.startsWith(pfx)).toList();
            }
            if ("holoheight".equals(sub) || "height".equals(sub)) {
                return List.of("2.65", "2.76", "2.85", "3.00");
            }
            if ("history".equals(sub) || "hist".equals(sub) || "menu".equals(sub)
                || "info".equals(sub) || "reset".equals(sub) || "devices".equals(sub) || "device".equals(sub)) {
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
