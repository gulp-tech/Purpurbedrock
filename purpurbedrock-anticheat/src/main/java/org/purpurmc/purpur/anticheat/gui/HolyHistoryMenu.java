package org.purpurmc.purpur.anticheat.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin;
import org.purpurmc.purpur.anticheat.config.ACConfig;
import org.purpurmc.purpur.anticheat.model.HistoryEntry;
import org.purpurmc.purpur.anticheat.model.PlayerProfile;
import org.purpurmc.purpur.anticheat.model.SeverityColor;
import org.purpurmc.purpur.anticheat.util.BedrockBridgeInfo;
import org.purpurmc.purpur.anticheat.util.ColorUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * HolyWorld-style 54-slot Staff Inspection, Player History (Green / Orange / Red + bottom legend),
 * and Live 21-Check In-Game Configurator GUI.
 */
public final class HolyHistoryMenu {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final PurpurBedrockACPlugin plugin;

    public HolyHistoryMenu(PurpurBedrockACPlugin plugin) {
        this.plugin = plugin;
    }

    public Inventory buildOverviewInventory(Player viewer, int page) {
        ACConfig cfg = plugin.cfg();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparingDouble((Player p) -> plugin.profile(p).avgScore)
            .thenComparingDouble(p -> plugin.profile(p).totalVl)
            .reversed());

        String rawTitle = cfg.menuOverviewTitle.replace("{count}", Integer.toString(online.size()));
        ACHolder holder = new ACHolder(ACHolder.MenuType.OVERVIEW, null, Math.max(0, page), ColorUtil.color(rawTitle));
        Inventory inv = holder.getInventory();

        ItemStack border = makeItem(cfg.matBorder, " ", List.of());
        for (int s = 0; s < 9; s++) {
            inv.setItem(s, border);
        }
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, border);
        }

        inv.setItem(4, makeItem(
            Material.BEACON,
            "&#A855F7&l🛡 PurpurBedrockAC 26.2 • Мониторинг",
            List.of(
                "&7Режим движка: " + plugin.engine().getGlobalStage().badge(),
                "&7Нагрузка пульса: &a" + plugin.engine().getLastPulseMicros() + " мкс/сек",
                "&7Активно модулей: &a" + cfg.countEnabledChecks() + "/" + ACConfig.ALL_CHECKS.size(),
                "&7Авто-наказания: " + (cfg.autoPunishEnabled ? "&eВКЛЮЧЕНЫ" : "&aВЫКЛ (только блокировка софта)"),
                "&7Игроков онлайн: &f" + online.size(),
                "",
                "&7Игроки отсортированы по &cриску (AVG)&7:",
                "&c🔴 Красные &8→ &6🟠 Оранжевые &8→ &a🟢 Зелёные",
                "&e▶ Нажмите на игрока, чтобы открыть его историю!"
            )
        ));

        int perPage = 36;
        int maxPage = Math.max(0, (online.size() - 1) / perPage);
        int safePage = Math.clamp(page, 0, maxPage);
        int startIdx = safePage * perPage;
        int endIdx = Math.min(online.size(), startIdx + perPage);

        int slot = 9;
        for (int i = startIdx; i < endIdx; i++) {
            Player target = online.get(i);
            PlayerProfile prof = plugin.profile(target);
            PlayerProfile.HistoryCounts counts = prof.countHistoryByColor();
            SeverityColor risk = classifyProfileRisk(prof, cfg);
            Material iconMat = materialForSeverity(risk, cfg);
            String avgColor = plugin.alerts().avgColorCode(prof.avgScore, cfg);

            inv.setItem(slot, makeItem(
                iconMat,
                risk.colorCode() + "&l" + target.getName() + " &8[" + avgColor + "AVG " + String.format(Locale.US, "%.1f", prof.avgScore) + "&8]",
                List.of(
                    "&7Статус: " + risk.colorCode() + risk.displayName(),
                    "&7Суммарный VL: " + risk.colorCode() + String.format(Locale.US, "%.1f", prof.totalVl) + " &8| &7CPS: &f" + String.format(Locale.US, "%.0f", prof.currentCps),
                    "&7История: &a🟢 " + counts.green() + " &8| &6🟠 " + counts.orange() + " &8| &c🔴 " + counts.red(),
                    "&7Устройство: &f" + BedrockBridgeInfo.getDevice(target),
                    "&7Управление: &f" + BedrockBridgeInfo.getInputDisplay(target) + " &8(&7" + BedrockBridgeInfo.getVersion(target) + "&8)",
                    "&7Пинг: &a" + BedrockBridgeInfo.getPing(target) + " мс &8| &7ИИ: " + prof.stage.badge(),
                    "",
                    "&e▶ Нажмите, чтобы открыть историю проверок!"
                )
            ));
            holder.setSlotTarget(slot, target.getUniqueId());
            slot++;
        }

        if (online.isEmpty()) {
            inv.setItem(22, makeItem(
                cfg.matGreen,
                "&a&l🟢 На сервере сейчас тихо",
                List.of(
                    "&7Античит находится в режиме &aглубокого сна (0% CPU)&7.",
                    "&7Как только игроки зайдут — здесь появится их список."
                )
            ));
        }

        placeBottomLegendAndControls(inv, viewer, null, safePage, maxPage, ACHolder.MenuType.OVERVIEW, cfg);
        return inv;
    }

    public Inventory buildChecksInventory(Player viewer) {
        ACConfig cfg = plugin.cfg();
        ACHolder holder = new ACHolder(ACHolder.MenuType.CHECKS_CONFIG, null, 0, ColorUtil.color(cfg.menuChecksTitle));
        Inventory inv = holder.getInventory();

        ItemStack border = makeItem(cfg.matBorder, " ", List.of());
        for (int s = 0; s < 9; s++) {
            inv.setItem(s, border);
        }
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, border);
        }

        inv.setItem(4, makeItem(
            Material.COMPARATOR,
            "&#A855F7&l⚙ Живая настройка модулей PurpurBedrockAC",
            List.of(
                "&7Активно проверок: &a" + cfg.countEnabledChecks() + " из " + ACConfig.ALL_CHECKS.size(),
                "&7Авто-наказания (кик/бан): " + (cfg.autoPunishEnabled ? "&eВКЛЮЧЕНЫ" : "&aВЫКЛЮЧЕНЫ (безопасный режим)"),
                "&7Защита пинга/TPS для кика: &f≤" + cfg.maxPingForAutoPunish + "мс &8/ &f≥" + cfg.minTpsForAutoPunish + " TPS",
                "",
                "&e• ЛКМ по модулю &8— &fвключить / выключить проверку",
                "&b• ПКМ по модулю &8— &fвкл/выкл Сетбэк / Отмену действия",
                "&7Все изменения сразу сохраняются в &fconfig.yml&7!"
            )
        ));

        int slot = 9;
        for (ACConfig.CheckMeta meta : ACConfig.ALL_CHECKS) {
            if (slot >= 45) {
                break;
            }
            ACConfig.CheckSettings s = cfg.check(meta.key());
            boolean mitigateOn = meta.movementStyle() ? s.setback : s.cancelAction;
            String mitigateLabel = meta.movementStyle() ? "Мягкий сетбэк" : "Отмена удара/блока";

            inv.setItem(slot, makeItem(
                s.enabled ? meta.icon() : Material.GRAY_DYE,
                (s.enabled ? "&a&l✔ " : "&c&l✖ ") + meta.title(),
                List.of(
                    "&7Код модуля: &f" + meta.key(),
                    "&7Описание: &f" + meta.shortDesc(),
                    "",
                    "&7Статус проверки: " + (s.enabled ? "&aВКЛЮЧЕНА" : "&cВЫКЛЮЧЕНА"),
                    "&7Блокировка софта (" + mitigateLabel + "): " + (mitigateOn ? "&aВКЛ" : "&cВЫКЛ"),
                    "&7Буфер: &f" + String.format(Locale.US, "%.1f", s.buffer)
                        + " &8| &7Спад: &f" + String.format(Locale.US, "%.2f", s.decay)
                        + " &8| &7Порог VL: &e" + String.format(Locale.US, "%.0f", s.maxVl),
                    "",
                    "&e▶ ЛКМ: переключить модуль",
                    "&b▶ ПКМ: переключить " + mitigateLabel.toLowerCase(Locale.ROOT)
                )
            ));
            holder.setSlotCheckKey(slot, meta.key());
            slot++;
        }

        placeBottomLegendAndControls(inv, viewer, null, 0, 0, ACHolder.MenuType.CHECKS_CONFIG, cfg);
        return inv;
    }

    public Inventory buildHistoryInventory(Player viewer, PlayerProfile targetProfile, int page) {
        ACConfig cfg = plugin.cfg();
        List<HistoryEntry> entries = targetProfile.snapshotHistory();
        int perPage = 36;
        int maxPage = Math.max(0, (entries.size() - 1) / perPage);
        int safePage = Math.clamp(page, 0, maxPage);

        String avgColor = plugin.alerts().avgColorCode(targetProfile.avgScore, cfg);
        String titleRaw = cfg.menuHistoryTitle
            .replace("{player}", targetProfile.playerName)
            .replace("{avg_color}", avgColor)
            .replace("{avg}", String.format(Locale.US, "%.1f", targetProfile.avgScore));

        ACHolder holder = new ACHolder(ACHolder.MenuType.PLAYER_HISTORY, targetProfile.uuid, safePage, ColorUtil.color(titleRaw));
        Inventory inv = holder.getInventory();

        ItemStack border = makeItem(cfg.matBorder, " ", List.of());
        for (int s = 0; s < 9; s++) {
            inv.setItem(s, border);
        }
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, border);
        }

        Player onlineTarget = Bukkit.getPlayer(targetProfile.uuid);
        PlayerProfile.HistoryCounts counts = targetProfile.countHistoryByColor();
        SeverityColor overallRisk = classifyProfileRisk(targetProfile, cfg);

        String device = onlineTarget != null ? BedrockBridgeInfo.getDevice(onlineTarget) : "Оффлайн";
        String input = onlineTarget != null ? BedrockBridgeInfo.getInputDisplay(onlineTarget) : "—";
        String version = onlineTarget != null ? BedrockBridgeInfo.getVersion(onlineTarget) : "—";
        String pingStr = onlineTarget != null ? (BedrockBridgeInfo.getPing(onlineTarget) + " мс") : "оффлайн";

        inv.setItem(4, makeItem(
            Material.RECOVERY_COMPASS,
            "&#A855F7&lДосье игрока: &f" + targetProfile.playerName,
            List.of(
                "&7Итоговая оценка: " + overallRisk.colorCode() + overallRisk.displayName(),
                "&7Показатель AVG: " + avgColor + String.format(Locale.US, "%.2f", targetProfile.avgScore)
                    + " &8| &7Общий VL: " + overallRisk.colorCode() + String.format(Locale.US, "%.1f", targetProfile.totalVl),
                "&7Записей: &a🟢 " + counts.green() + " &8| &6🟠 " + counts.orange() + " &8| &c🔴 " + counts.red(),
                "&7Устройство: &f" + device + " &8| &7Ввод: &f" + input,
                "&7Версия клиента: &b" + version + " &8| &7Пинг: &a" + pingStr,
                "&7Режим античита для игрока: " + targetProfile.stage.badge()
            )
        ));

        if (entries.isEmpty()) {
            inv.setItem(22, makeItem(
                cfg.matGreen,
                "&a&l🟢 ИСТОРИЯ ЧИСТА",
                List.of(
                    "&7У игрока &f" + targetProfile.playerName + " &7нет ни одного",
                    "&7подозрительного или красного срабатывания.",
                    "",
                    "&a✔ Игрок играет честно."
                )
            ));
        } else {
            long nowMs = System.currentTimeMillis();
            int start = safePage * perPage;
            int end = Math.min(entries.size(), start + perPage);
            int slot = 9;
            for (int i = start; i < end; i++) {
                HistoryEntry e = entries.get(i);
                Material blockMat = materialForSeverity(e.severity(), cfg);
                long agoSec = Math.max(0L, (nowMs - e.timestampMs()) / 1000L);
                String agoStr = agoSec < 60
                    ? (agoSec + " сек. назад")
                    : ((agoSec / 60L) + " мин. " + (agoSec % 60L) + " сек. назад");
                String timeClock = TIME_FMT.format(Instant.ofEpochMilli(e.timestampMs()));

                String badgeTitle = switch (e.severity()) {
                    case GREEN -> "&a&l🟢 " + e.checkName();
                    case ORANGE -> "&6&l🟠 Подозрение: &f" + e.checkName();
                    case RED -> "&c&l🔴 СОФТ / ДЕТЕКТ: &f" + e.checkName();
                };

                inv.setItem(slot++, makeItem(
                    blockMat,
                    badgeTitle + " &8(&7" + timeClock + "&8)",
                    List.of(
                        "&7Уровень: " + e.severity().colorCode() + e.severity().displayName(),
                        "&7Время: &f" + agoStr + " &8(&7" + timeClock + "&8)",
                        "&7Детали: &f" + e.details(),
                        "&7VL проверки: " + e.severity().colorCode() + String.format(Locale.US, "%.1f", e.vlAfter())
                            + " &8| &7AVG: &e" + String.format(Locale.US, "%.1f", e.avgSnapshot()),
                        "&7Пинг: &a" + e.pingMs() + " мс &8| &7TPS сервера: &a" + String.format(Locale.US, "%.1f", e.serverTps()),
                        "&7Устройство: &f" + e.device() + " &8• &f" + e.input(),
                        "&7Действие защиты: &b" + e.actionTaken()
                    )
                ));
            }
        }

        placeBottomLegendAndControls(inv, viewer, targetProfile, safePage, maxPage, ACHolder.MenuType.PLAYER_HISTORY, cfg);
        return inv;
    }

    private void placeBottomLegendAndControls(
        Inventory inv,
        Player viewer,
        PlayerProfile targetProfile,
        int page,
        int maxPage,
        ACHolder.MenuType menuType,
        ACConfig cfg
    ) {
        if (page > 0) {
            inv.setItem(45, makeItem(
                Material.ARROW,
                "&e⬅ Предыдущая страница &8(&7" + page + "/" + (maxPage + 1) + "&8)",
                List.of("&7Перейти к предыдущим записям")
            ));
        } else if (menuType != ACHolder.MenuType.OVERVIEW) {
            inv.setItem(45, makeItem(
                Material.ARROW,
                "&e⬅ К общему списку игроков",
                List.of("&7Вернуться в панель обзора всех игроков")
            ));
        }

        // Slots 46, 47, 48: HolyWorld-style Green / Orange / Red instruction blocks!
        inv.setItem(46, makeItem(cfg.matGreen, cfg.legendGreenName, cfg.legendGreenLore));
        inv.setItem(47, makeItem(cfg.matOrange, cfg.legendOrangeName, cfg.legendOrangeLore));
        inv.setItem(48, makeItem(cfg.matRed, cfg.legendRedName, cfg.legendRedLore));

        if (viewer != null) {
            boolean holoOn = plugin.holograms().isEnabledFor(viewer);
            boolean hasHoloPerm = viewer.hasPermission(cfg.permHologram);
            inv.setItem(49, makeItem(
                holoOn ? Material.ENDER_EYE : Material.ENDER_PEARL,
                "&d&l👁 Голограмма AVG над игроками",
                List.of(
                    "&7Статус: " + (holoOn ? "&aВКЛЮЧЕНА" : "&cВЫКЛЮЧЕНА"),
                    "&7Право: &f" + cfg.permHologram + (hasHoloPerm ? " &a(есть)" : " &c(нет)"),
                    "&7Показывает компактный &fAVG / VL / Ping",
                    "&7над игроками только для вас без нагрузки!",
                    "",
                    hasHoloPerm ? "&e▶ Нажмите, чтобы переключить!" : "&c✖ Недостаточно прав"
                )
            ));

            boolean alertsOn = plugin.alerts().hasAlertsEnabled(viewer);
            boolean hasAlertPerm = viewer.hasPermission(cfg.permAlerts);
            inv.setItem(50, makeItem(
                alertsOn ? Material.BELL : Material.GRAY_DYE,
                "&6&l🔔 Уведомления (Алерты) в чат",
                List.of(
                    "&7Статус: " + (alertsOn ? "&aВКЛЮЧЕНЫ" : "&cВЫКЛЮЧЕНЫ"),
                    "&7Право: &f" + cfg.permAlerts + (hasAlertPerm ? " &a(есть)" : " &c(нет)"),
                    "",
                    hasAlertPerm ? "&e▶ Нажмите, чтобы переключить!" : "&c✖ Недостаточно прав"
                )
            ));
        }

        if (menuType == ACHolder.MenuType.OVERVIEW) {
            if (viewer == null || viewer.hasPermission(cfg.permAdmin)) {
                inv.setItem(51, makeItem(
                    Material.COMPARATOR,
                    "&b&l⚙ Настройка модулей (" + cfg.countEnabledChecks() + "/" + ACConfig.ALL_CHECKS.size() + ")",
                    List.of(
                        "&7Гибкое включение/выключение любой из",
                        "&725 проверок, сетбэков и отмены ударов",
                        "&7прямо из игры с автосохранением!",
                        "",
                        "&e▶ Нажмите, чтобы открыть настройки"
                    )
                ));
                inv.setItem(52, makeItem(
                    cfg.autoPunishEnabled ? Material.TNT : Material.SHIELD,
                    "&e&l⚡ Авто-кик / Авто-наказания",
                    List.of(
                        "&7Статус: " + (cfg.autoPunishEnabled
                            ? "&eВКЛЮЧЕНЫ (при VL ≥ порога)"
                            : "&aВЫКЛЮЧЕНЫ (не кикает просто так!)"),
                        "&7Даже при выключенном авто-кике античит",
                        "&7блокирует софт сетбэками и отменой ударов!",
                        "",
                        "&e▶ Нажмите для переключения"
                    )
                ));
            }
        } else if (menuType == ACHolder.MenuType.CHECKS_CONFIG) {
            if (viewer == null || viewer.hasPermission(cfg.permAdmin)) {
                inv.setItem(52, makeItem(
                    cfg.autoPunishEnabled ? Material.TNT : Material.SHIELD,
                    "&e&l⚡ Авто-кик / Авто-наказания",
                    List.of(
                        "&7Статус: " + (cfg.autoPunishEnabled
                            ? "&eВКЛЮЧЕНЫ (с защитой пинга/TPS)"
                            : "&aВЫКЛЮЧЕНЫ (0 ложных киков!)"),
                        "",
                        "&e▶ Нажмите для переключения"
                    )
                ));
            }
        } else if (menuType == ACHolder.MenuType.PLAYER_HISTORY && targetProfile != null) {
            Player onlineTarget = Bukkit.getPlayer(targetProfile.uuid);
            if (onlineTarget != null) {
                inv.setItem(51, makeItem(
                    Material.FEATHER,
                    "&b&l🎯 Телепортироваться к " + targetProfile.playerName,
                    List.of(
                        "&7Мир: &f" + onlineTarget.getWorld().getName(),
                        "&e▶ Нажмите для мгновенной телепортации"
                    )
                ));
            }
            if (viewer == null || viewer.hasPermission(cfg.permAdmin)) {
                inv.setItem(52, makeItem(
                    Material.BRUSH,
                    "&c&l🧹 Сбросить VL и историю игрока",
                    List.of(
                        "&7Очищает накопленный VL, AVG и историю",
                        "&7проверок для &f" + targetProfile.playerName + "&7.",
                        "",
                        "&e▶ Нажмите для очистки"
                    )
                ));
            }
        }

        if (page < maxPage) {
            inv.setItem(53, makeItem(
                Material.ARROW,
                "&eСледующая страница ➡ &8(&7" + (page + 2) + "/" + (maxPage + 1) + "&8)",
                List.of("&7Перейти к более ранним записям")
            ));
        }
    }

    public void openOverview(Player viewer, int page) {
        viewer.openInventory(buildOverviewInventory(viewer, page));
    }

    public void openChecksConfig(Player viewer) {
        viewer.openInventory(buildChecksInventory(viewer));
    }

    public void openPlayerHistory(Player viewer, PlayerProfile targetProfile, int page) {
        viewer.openInventory(buildHistoryInventory(viewer, targetProfile, page));
    }

    public void handleMenuClick(Player viewer, ACHolder holder, InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != holder.getInventory()) {
            return;
        }
        ACConfig cfg = plugin.cfg();
        int slot = event.getSlot();

        if (slot == 49) {
            if (!viewer.hasPermission(cfg.permHologram)) {
                viewer.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permHologram + " &cна голограмму AVG!"));
                return;
            }
            boolean state = plugin.holograms().toggle(viewer);
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, state ? 1.3f : 0.9f);
            reopenCurrent(viewer, holder);
            return;
        }

        if (slot == 50) {
            if (!viewer.hasPermission(cfg.permAlerts)) {
                viewer.sendMessage(ColorUtil.color(cfg.alertPrefix + "&cУ вас нет права &f" + cfg.permAlerts + " &cна алерты!"));
                return;
            }
            boolean state = plugin.alerts().toggleAlerts(viewer);
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, state ? 1.3f : 0.9f);
            reopenCurrent(viewer, holder);
            return;
        }

        if (holder.menuType() == ACHolder.MenuType.OVERVIEW) {
            if (slot == 45 && holder.page() > 0) {
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                openOverview(viewer, holder.page() - 1);
            } else if (slot == 51 && viewer.hasPermission(cfg.permAdmin)) {
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                openChecksConfig(viewer);
            } else if (slot == 52 && viewer.hasPermission(cfg.permAdmin)) {
                boolean on = cfg.toggleAutoPunish(plugin);
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, on ? 1.3f : 0.9f);
                openOverview(viewer, holder.page());
            } else if (slot == 53 && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                openOverview(viewer, holder.page() + 1);
            } else if (slot >= 9 && slot <= 44) {
                UUID targetId = holder.getSlotTarget(slot);
                if (targetId != null) {
                    PlayerProfile targetProf = plugin.profileByUuid(targetId);
                    if (targetProf != null) {
                        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                        openPlayerHistory(viewer, targetProf, 0);
                    }
                }
            }
            return;
        }

        if (holder.menuType() == ACHolder.MenuType.CHECKS_CONFIG) {
            if (slot == 45) {
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                openOverview(viewer, 0);
                return;
            }
            if (!viewer.hasPermission(cfg.permAdmin)) {
                return;
            }
            if (slot == 52) {
                boolean on = cfg.toggleAutoPunish(plugin);
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, on ? 1.3f : 0.9f);
                openChecksConfig(viewer);
                return;
            }
            String checkKey = holder.getSlotCheckKey(slot);
            if (checkKey != null) {
                boolean state;
                if (event.isRightClick()) {
                    state = cfg.toggleCheckMitigation(plugin, checkKey);
                } else {
                    state = cfg.toggleCheckEnabled(plugin, checkKey);
                }
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, state ? 1.3f : 0.9f);
                openChecksConfig(viewer);
            }
            return;
        }

        // PLAYER_HISTORY clicks
        PlayerProfile targetProf = holder.targetUuid() != null ? plugin.profileByUuid(holder.targetUuid()) : null;
        if (targetProf == null) {
            openOverview(viewer, 0);
            return;
        }

        if (slot == 45) {
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
            if (holder.page() > 0) {
                openPlayerHistory(viewer, targetProf, holder.page() - 1);
            } else {
                openOverview(viewer, 0);
            }
        } else if (slot == 53 && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
            openPlayerHistory(viewer, targetProf, holder.page() + 1);
        } else if (slot == 51) {
            Player onlineTarget = Bukkit.getPlayer(targetProf.uuid);
            if (onlineTarget != null) {
                viewer.closeInventory();
                viewer.teleport(onlineTarget.getLocation());
                viewer.sendMessage(ColorUtil.color(cfg.alertPrefix + "&aВы телепортированы к игроку &f" + onlineTarget.getName() + "&a."));
            }
        } else if (slot == 52 && viewer.hasPermission(cfg.permAdmin)) {
            targetProf.resetViolations();
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            openPlayerHistory(viewer, targetProf, 0);
        }
    }

    private void reopenCurrent(Player viewer, ACHolder holder) {
        if (holder.menuType() == ACHolder.MenuType.OVERVIEW) {
            openOverview(viewer, holder.page());
        } else if (holder.menuType() == ACHolder.MenuType.CHECKS_CONFIG) {
            openChecksConfig(viewer);
        } else {
            PlayerProfile prof = holder.targetUuid() != null ? plugin.profileByUuid(holder.targetUuid()) : null;
            if (prof != null) {
                openPlayerHistory(viewer, prof, holder.page());
            } else {
                openOverview(viewer, 0);
            }
        }
    }

    private SeverityColor classifyProfileRisk(PlayerProfile prof, ACConfig cfg) {
        PlayerProfile.HistoryCounts counts = prof.countHistoryByColor();
        if (counts.red() > 0 || prof.avgScore > cfg.hologramOrangeMaxAvg || prof.totalVl >= cfg.redVlThreshold) {
            return SeverityColor.RED;
        }
        if (counts.orange() > 0 || prof.avgScore > cfg.hologramGreenMaxAvg || prof.totalVl >= cfg.orangeVlThreshold) {
            return SeverityColor.ORANGE;
        }
        return SeverityColor.GREEN;
    }

    private static Material materialForSeverity(SeverityColor color, ACConfig cfg) {
        return switch (color) {
            case GREEN -> cfg.matGreen;
            case ORANGE -> cfg.matOrange;
            case RED -> cfg.matRed;
        };
    }

    private static ItemStack makeItem(Material mat, String displayName, List<String> loreLines) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorUtil.itemText(displayName));
            if (!loreLines.isEmpty()) {
                meta.lore(ColorUtil.itemLore(loreLines));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
