package org.purpurmc.purpur.bedrock.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.bedrock.BedrockBridgeBootstrap;
import org.purpurmc.purpur.bedrock.BedrockChunkDowngrader;
import org.purpurmc.purpur.bedrock.BedrockChunkHelper;
import org.purpurmc.purpur.bedrock.BedrockPlaceholderHook;
import org.purpurmc.purpur.bedrock.menu.JavaChestMenuManager;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PurpurBedrockCommand extends Command {
    public PurpurBedrockCommand(String name) {
        super(name);
        this.description = "Purpurbedrock 26.2 — управление ядром Bedrock-only, чанками, PAPI, Anti-Xray, LAN и Java-меню";
        this.usageMessage = "/purpurbedrock [status | menu | chunks | papi | antixray | reload]";
        this.setAliases(List.of("pb", "bedrockcore"));
        this.setPermission("purpurbedrock.admin");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("status", "menu", "chunks", "papi", "antixray", "reload")
                .filter(opt -> opt.startsWith(prefix))
                .toList();
        }
        if (args.length == 2 && ("chunks".equalsIgnoreCase(args[0]) || "papi".equalsIgnoreCase(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        if ("menu".equals(sub)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Команда /purpurbedrock menu доступна только игроку в игре.", NamedTextColor.RED));
                return true;
            }
            JavaChestMenuManager.openControlPanel(player);
            return true;
        }

        if (!testPermission(sender)) {
            return true;
        }

        if ("reload".equals(sub)) {
            MinecraftServer console = MinecraftServer.getServer();
            PurpurConfig.init((File) console.options.valueOf("purpur-settings"));
            for (ServerLevel level : console.getAllLevels()) {
                level.purpurConfig.init();
            }
            BedrockChunkHelper.applyRuntimeChunkTuning();
            BedrockPlaceholderHook.initAfterPlugins();
            sender.sendMessage(Component.text("[Purpurbedrock] Конфигурация Purpurbedrock 26.2 успешно перезагружена.", NamedTextColor.GREEN));
            return true;
        }

        if ("chunks".equals(sub)) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage(Component.text("Использование: /purpurbedrock chunks <игрок>", NamedTextColor.YELLOW));
                return true;
            }
            int radius = BedrockChunkHelper.forceResyncPlayerChunks(((CraftPlayer) target).getHandle());
            sender.sendMessage(Component.text(
                String.format("[Purpurbedrock] Область прогрузки чанков для %s синхронизирована (радиус: %d чанков).", target.getName(), radius),
                NamedTextColor.GREEN
            ));
            return true;
        }

        if ("papi".equals(sub)) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
            boolean papiLoaded = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
            sender.sendMessage(Component.text("=== Встроенные плейсхолдеры %purpurbedrock_*% ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text(
                " • Статус PlaceholderAPI: " + (BedrockPlaceholderHook.isRegistered()
                    ? "АКТИВЕН (%purpurbedrock_*% зарегистрирован из ядра)"
                    : (papiLoaded ? "ГОТОВ" : "Ожидание плагина PlaceholderAPI в plugins/")),
                BedrockPlaceholderHook.isRegistered() ? NamedTextColor.GREEN : NamedTextColor.YELLOW
            ));
            if (target != null) {
                sender.sendMessage(Component.text(" • Игрок: " + target.getName(), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("   %purpurbedrock_device%  -> " + BedrockPlaceholderHook.resolveOnline(target, "device"), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("   %purpurbedrock_input%   -> " + BedrockPlaceholderHook.resolveOnline(target, "input"), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("   %purpurbedrock_version% -> " + BedrockPlaceholderHook.resolveOnline(target, "version"), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("   %purpurbedrock_ping%    -> " + BedrockPlaceholderHook.resolveOnline(target, "ping") + " мс", NamedTextColor.WHITE));
            } else {
                sender.sendMessage(Component.text(" • Доступно: %purpurbedrock_device%, %purpurbedrock_input%, %purpurbedrock_version%, %purpurbedrock_ping%", NamedTextColor.AQUA));
            }
            return true;
        }

        if ("selftest".equals(sub)) {
            ServerLevel level = MinecraftServer.getServer().overworld();
            String report = BedrockChunkDowngrader.runSelfTest(level);
            String protoReport = BedrockBridgeBootstrap.runProtocolSelfTest();
            String papiReport = BedrockPlaceholderHook.runSelfTest();
            sender.sendMessage(Component.text("[Purpurbedrock] " + report, NamedTextColor.GREEN));
            sender.sendMessage(Component.text("[Purpurbedrock] " + protoReport, NamedTextColor.GREEN));
            sender.sendMessage(Component.text("[Purpurbedrock] " + papiReport, NamedTextColor.GREEN));
            return true;
        }

        if ("antixray".equals(sub)) {
            sender.sendMessage(Component.text("=== Статус Bedrock Anti-Xray по мирам ===", NamedTextColor.GOLD));
            for (World world : Bukkit.getWorlds()) {
                var xray = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle().paperConfig().anticheat.antiXray;
                sender.sendMessage(Component.text(String.format(
                    " • %s (%s): %s | Режим: %s | Высота: <= %d | Под лавой: %s | Руд скрыто: %d",
                    world.getName(),
                    world.getEnvironment().name(),
                    xray.enabled ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН",
                    xray.engineMode,
                    xray.maxBlockHeight,
                    xray.lavaObscures ? "ДА" : "НЕТ",
                    xray.hiddenBlocks != null ? xray.hiddenBlocks.size() : 0
                ), xray.enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            return true;
        }

        boolean combatEnabled = !Bukkit.getWorlds().isEmpty()
            && ((org.bukkit.craftbukkit.CraftWorld) Bukkit.getWorlds().getFirst()).getHandle().purpurConfig.bedrockCombatEnabled;
        double tps1m = Math.min(20.0, Bukkit.getTPS()[0]);

        sender.sendMessage(Component.text("=== Статус ядра Purpurbedrock 26.2 (STABLE) ===", NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text(" • Версия сервера: " + Bukkit.getMinecraftVersion() + " (" + Bukkit.getBukkitVersion() + ") | TPS: " + String.format(Locale.US, "%.2f", tps1m), NamedTextColor.GREEN));
        sender.sendMessage(Component.text(" • Вход только с Bedrock (блок Java): " + (PurpurConfig.bedrockOnlyAllowBedrock ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Автономный протокол Bedrock: " + (BedrockBridgeBootstrap.isProtocolActive()
            ? String.format("АКТИВЕН (RakNet/Signaling %d, NetherNet UDP %d, LAN Миры UDP %d)",
                BedrockBridgeBootstrap.getActiveBedrockPort(),
                BedrockBridgeBootstrap.getActiveWebrtcPort(),
                BedrockBridgeBootstrap.getActiveLanDiscoveryPort())
            : "ОЖИДАНИЕ"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Боевая система Bedrock (1.8 без КД): " + (combatEnabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Anti-Xray под Bedrock (Алмазы + Незерит): " + (PurpurConfig.bedrockAntiXrayEnabled ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Погрузка чанков Bedrock (Moonrise + Publisher Sync): " + (PurpurConfig.bedrockChunkLoadingFix ? "УСИЛЕНА" : "СТАНДАРТ"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Встроенные плейсхолдеры (%purpurbedrock_*%): "
            + (BedrockPlaceholderHook.isRegistered() ? "АКТИВНЫ В PAPI" : (PurpurConfig.bedrockPlaceholdersEnabled ? "ГОТОВЫ (авто-подключение к PAPI)" : "ВЫКЛ")), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Защита от флагов движения и фантомных блоков: "
            + ((PurpurConfig.bedrockMovementLeniency && PurpurConfig.bedrockPreventGhostBlocks) ? "ВКЛЮЧЕНА" : "ЧАСТИЧНО"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Java-меню (DeluxeMenus / Chest GUI): " + (PurpurConfig.bedrockMenusEnabled ? "ВКЛЮЧЕНЫ" : "ВЫКЛЮЧЕНЫ"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Мульти-версия Bedrock: 1.21.0 — 26.52 (v685 — v2193, 23 протокола)", NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Окно ожидания диалога (gui.waitingForResponse): УБРАНО", NamedTextColor.AQUA));
        String df = BedrockBridgeBootstrap.getActiveDataFolder();
        sender.sendMessage(Component.text(String.format(" • Папки Bedrock: %s/cache (player_skulls) | %s/custom_mappings | %s/extensions | %s/packs", df, df, df, df), NamedTextColor.AQUA));
        return true;
    }
}
