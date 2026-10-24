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
import org.bukkit.entity.Player;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.bedrock.BedrockBridgeBootstrap;
import org.purpurmc.purpur.bedrock.menu.JavaChestMenuManager;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PurpurBedrockCommand extends Command {
    public PurpurBedrockCommand(String name) {
        super(name);
        this.description = "Purpurbedrock 26.2 — управление ядром Bedrock-only, Anti-Xray, LAN и Java-меню";
        this.usageMessage = "/purpurbedrock [status | menu | antixray | reload]";
        this.setAliases(List.of("pb", "bedrockcore"));
        this.setPermission("purpurbedrock.admin");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("status", "menu", "antixray", "reload")
                .filter(opt -> opt.startsWith(prefix))
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
            sender.sendMessage(Component.text("[Purpurbedrock] Конфигурация Purpurbedrock 26.2 успешно перезагружена.", NamedTextColor.GREEN));
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
        sender.sendMessage(Component.text(" • Защита от флагов движения и фантомных блоков: "
            + ((PurpurConfig.bedrockMovementLeniency && PurpurConfig.bedrockPreventGhostBlocks) ? "ВКЛЮЧЕНА" : "ЧАСТИЧНО"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" • Java-меню (DeluxeMenus / Chest GUI): " + (PurpurConfig.bedrockMenusEnabled ? "ВКЛЮЧЕНЫ" : "ВЫКЛЮЧЕНЫ"), NamedTextColor.AQUA));
        return true;
    }
}
