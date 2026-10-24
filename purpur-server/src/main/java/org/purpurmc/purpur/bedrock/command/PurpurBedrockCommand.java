package org.purpurmc.purpur.bedrock.command;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.bedrock.BedrockBridgeBootstrap;
import org.purpurmc.purpur.bedrock.menu.JavaChestMenuManager;

public class PurpurBedrockCommand extends Command {
    public PurpurBedrockCommand(final String name) {
        super(name);
        this.description = "Purpurbedrock server management (Bedrock-only gatekeeper, Bedrock combat, Java chest menus)";
        this.usageMessage = "/purpurbedrock [status | reload | openmenu <menu> [player]]";
        this.setPermission("bukkit.command.purpur");
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final Location location) {
        if (!testPermissionSilent(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Stream.of("status", "reload", "openmenu")
                .filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("openmenu")) {
            return JavaChestMenuManager.getMenuIds().stream()
                .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("openmenu")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (!testPermission(sender)) {
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            MinecraftServer mc = MinecraftServer.getServer();
            ServerLevel defaultLevel = mc.overworld();
            boolean combatOn = defaultLevel != null && defaultLevel.purpurConfig.bedrockCombatEnabled;

            sender.sendMessage(Component.text("=== Purpurbedrock 26.3 Status ===", NamedTextColor.AQUA));
            sender.sendMessage(Component.text("• Вход только с Bedrock (блок Java): ", NamedTextColor.GRAY)
                .append(Component.text(PurpurConfig.bedrockOnlyAllowBedrock ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН",
                    PurpurConfig.bedrockOnlyAllowBedrock ? NamedTextColor.GREEN : NamedTextColor.RED)));

            String bridgeStatus = BedrockBridgeBootstrap.isEmbeddedBridgeActive()
                ? "Встроенный Geyser (UDP " + BedrockBridgeBootstrap.getDefaultBedrockPort() + ")"
                : (BedrockBridgeBootstrap.isExternalGeyserDetected() ? "Пользовательский Geyser в plugins/" : "Внешний / не загружен");
            sender.sendMessage(Component.text("• Мост Bedrock: ", NamedTextColor.GRAY)
                .append(Component.text(bridgeStatus, NamedTextColor.GREEN)));

            sender.sendMessage(Component.text("• Боёвка Bedrock (без КД 1.9, криты в спринте, щит на Shift): ", NamedTextColor.GRAY)
                .append(Component.text(combatOn ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА",
                    combatOn ? NamedTextColor.GREEN : NamedTextColor.RED)));

            sender.sendMessage(Component.text("• Меню как на Java (Chest GUI + ресинк кликов): ", NamedTextColor.GRAY)
                .append(Component.text(
                    "ВКЛЮЧЕНЫ (встроенных меню: " + JavaChestMenuManager.getLoadedMenuCount() + ")",
                    NamedTextColor.GREEN)));

            sender.sendMessage(Component.text("• Загружено Java-плагинов: ", NamedTextColor.GRAY)
                .append(Component.text(Integer.toString(Bukkit.getPluginManager().getPlugins().length), NamedTextColor.YELLOW)));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            MinecraftServer console = MinecraftServer.getServer();
            PurpurConfig.init((File) console.options.valueOf("purpur-settings"));
            for (ServerLevel level : console.getAllLevels()) {
                level.purpurConfig.init();
                level.resetBreedingCooldowns();
            }
            JavaChestMenuManager.initAfterPlugins();
            console.server.reloadCount++;
            sender.sendMessage(Component.text("[Purpurbedrock] Конфигурация purpur.yml и menus.yml успешно перезагружена.", NamedTextColor.GREEN));
            return true;
        }

        if (args[0].equalsIgnoreCase("openmenu") && args.length >= 2) {
            Player target;
            if (args.length >= 3) {
                target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(Component.text("Игрок не найден: " + args[2], NamedTextColor.RED));
                    return true;
                }
            } else if (sender instanceof Player p) {
                target = p;
            } else {
                sender.sendMessage(Component.text("Укажите ник игрока: /purpurbedrock openmenu <menu> <player>", NamedTextColor.RED));
                return true;
            }

            if (!JavaChestMenuManager.openMenu(target, args[1])) {
                sender.sendMessage(Component.text("Меню не найдено: " + args[1], NamedTextColor.RED));
            }
            return true;
        }

        sender.sendMessage(Component.text("Использование: " + this.usageMessage, NamedTextColor.RED));
        return false;
    }
}
