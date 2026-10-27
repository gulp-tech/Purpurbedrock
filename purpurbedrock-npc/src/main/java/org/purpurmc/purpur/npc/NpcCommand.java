package org.purpurmc.purpur.npc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class NpcCommand implements CommandExecutor, TabCompleter {
    private final PurpurBedrockNpcPlugin plugin;
    private final NpcManager manager;

    NpcCommand(PurpurBedrockNpcPlugin plugin, NpcManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(sender, args);
            case "remove", "delete" -> remove(sender, args);
            case "movehere" -> moveHere(sender, args);
            case "type" -> type(sender, args);
            case "holo", "hologram" -> hologram(sender, args);
            case "holoheight" -> hologramHeight(sender, args);
            case "action" -> action(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            case "selftest" -> success(sender, NpcManager.runSelfTest());
            default -> error(sender, "Неизвестная команда. Используйте /pbnpc help");
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Создавать NPC можно только из игры.");
            return;
        }
        if (args.length < 3) {
            error(sender, "Использование: /pbnpc create <id> <тип_моба>");
            return;
        }
        EntityType type = NpcManager.parseMobType(args[2]);
        if (type == null) {
            error(sender, "Тип не является обычным мобом без AI: " + args[2]);
            return;
        }
        Location spawnLocation = placementInFront(player);
        String normalizedId = NpcManager.normalizeId(args[1]);
        NpcManager.CreateResult result = manager.create(normalizedId, type, spawnLocation);
        switch (result) {
            case CREATED -> success(sender, "NPC '" + normalizedId + "' создан перед вами. AI=OFF, задач=0.");
            case RESPAWNED -> success(sender, "NPC '" + normalizedId + "' уже существовал и пересоздан перед вами.");
            case INVALID_ID -> error(sender, "NPC не создан: ID пустой или некорректный.");
            case INVALID_TYPE -> error(sender, "NPC не создан: '" + args[2] + "' не является обычным мобом.");
            case SPAWN_FAILED -> error(sender, "NPC не создан: мир или позиция не позволяют создать сущность. Проверьте консоль.");
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Использование: /pbnpc remove <id>");
            return;
        }
        if (manager.remove(args[1])) {
            success(sender, "NPC удалён.");
        } else {
            notFound(sender, args[1]);
        }
    }

    private void moveHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Команда доступна только из игры.");
            return;
        }
        if (args.length < 2) {
            error(sender, "Использование: /pbnpc movehere <id>");
            return;
        }
        if (manager.moveHere(args[1], player.getLocation())) {
            success(sender, "NPC перемещён к вам.");
        } else {
            notFound(sender, args[1]);
        }
    }

    private void type(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "Использование: /pbnpc type <id> <тип_моба>");
            return;
        }
        EntityType type = NpcManager.parseMobType(args[2]);
        if (type != null && manager.setType(args[1], type)) {
            success(sender, "Тип NPC изменён на " + type.name() + ".");
        } else {
            error(sender, "NPC не найден или указан не mob-тип.");
        }
    }

    private void hologram(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "Использование: /pbnpc holo <id> <set|clear> [текст; | = новая строка]");
            return;
        }
        String operation = args[2].toLowerCase(Locale.ROOT);
        String text;
        if ("clear".equals(operation)) {
            text = "";
        } else if ("set".equals(operation) && args.length >= 4) {
            text = join(args, 3);
        } else {
            error(sender, "Использование: /pbnpc holo <id> set <MiniMessage текст | следующая строка>");
            return;
        }
        if (manager.setHologram(args[1], text)) {
            success(sender, "Голограмма обновлена без пересоздания NPC.");
        } else {
            notFound(sender, args[1]);
        }
    }

    private void hologramHeight(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "Использование: /pbnpc holoheight <id> <0.5..10>");
            return;
        }
        try {
            double height = Double.parseDouble(args[2]);
            if (manager.setHologramHeight(args[1], height)) {
                success(sender, "Высота голограммы: " + height);
            } else {
                error(sender, "NPC не найден или высота вне диапазона 0.5–10.");
            }
        } catch (NumberFormatException ignored) {
            error(sender, "Высота должна быть числом.");
        }
    }

    private void action(CommandSender sender, String[] args) {
        if (args.length < 3) {
            actionHelp(sender);
            return;
        }
        String id = args[1];
        if (manager.get(id) == null) {
            notFound(sender, id);
            return;
        }
        String operation = args[2].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "list" -> {
                List<String> actions = manager.actions(id);
                sender.sendMessage(Component.text("Действия NPC " + id + " (" + actions.size() + "): ", NamedTextColor.GOLD));
                for (int i = 0; i < actions.size(); i++) {
                    sender.sendMessage(Component.text(" " + (i + 1) + ". " + actions.get(i), NamedTextColor.AQUA));
                }
            }
            case "clear" -> {
                manager.clearActions(id);
                success(sender, "Все действия NPC очищены.");
            }
            case "remove" -> {
                if (args.length < 4) {
                    error(sender, "Использование: /pbnpc action <id> remove <номер>");
                    return;
                }
                try {
                    if (manager.removeAction(id, Integer.parseInt(args[3]))) {
                        success(sender, "Действие удалено.");
                    } else {
                        error(sender, "Такого номера действия нет.");
                    }
                } catch (NumberFormatException ignored) {
                    error(sender, "Номер действия должен быть числом.");
                }
            }
            case "add" -> addAction(sender, id, args);
            default -> actionHelp(sender);
        }
    }

    private void addAction(CommandSender sender, String id, String[] args) {
        if (args.length < 5) {
            actionHelp(sender);
            return;
        }
        String type = args[3].toLowerCase(Locale.ROOT);
        String value = join(args, 4);
        if ("teleport".equals(type) && "here".equalsIgnoreCase(value)) {
            if (!(sender instanceof Player player)) {
                error(sender, "Значение here доступно только игроку.");
                return;
            }
            Location location = player.getLocation();
            value = String.join(";",
                location.getWorld().getName(),
                Double.toString(location.getX()),
                Double.toString(location.getY()),
                Double.toString(location.getZ()),
                Float.toString(location.getYaw()),
                Float.toString(location.getPitch())
            );
        }
        String encoded = type + ":" + value;
        if (manager.addAction(id, encoded)) {
            success(sender, "Действие добавлено: " + encoded);
        } else {
            error(sender, "Тип действия: console, player, message, menu или teleport.");
        }
    }

    private void list(CommandSender sender) {
        CollectionView view = new CollectionView(manager.definitions().stream().map(npc -> npc.id).sorted().toList());
        sender.sendMessage(Component.text("NPC (" + view.ids.size() + "): " + String.join(", ", view.ids), NamedTextColor.GOLD));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Использование: /pbnpc info <id>");
            return;
        }
        NpcDefinition npc = manager.get(args[1]);
        if (npc == null) {
            notFound(sender, args[1]);
            return;
        }
        sender.sendMessage(Component.text("=== NPC " + npc.id + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Тип: " + npc.type + " | Мир: " + npc.location.getWorld().getName(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(String.format(Locale.US, "Позиция: %.2f %.2f %.2f | Holo: %.2f", npc.location.getX(), npc.location.getY(), npc.location.getZ(), npc.hologramHeight), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Голограмма: " + npc.hologram + " | Действий: " + npc.actions.size(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("AI=OFF | gravity=OFF | collision=OFF | scheduler tasks=0", NamedTextColor.GREEN));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        manager.load();
        success(sender, "Конфигурация и NPC перезагружены.");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(Component.text("=== PurpurBedrockNPC 26.2 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/pbnpc create <id> <mob> — создать неподвижного моба", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pbnpc remove <id> | movehere <id> | type <id> <mob>", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pbnpc holo <id> set <текст | новая строка> | clear", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pbnpc holoheight <id> <0.5..10>", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pbnpc action <id> add <console|player|message|menu|teleport> <значение>", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pbnpc action <id> <list|remove номер|clear>", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pbnpc list | info <id> | reload | selftest", NamedTextColor.AQUA));
    }

    private static void actionHelp(CommandSender sender) {
        error(sender, "Использование: /pbnpc action <id> add <console|player|message|menu|teleport> <значение>");
        sender.sendMessage(Component.text("teleport here — сохранить вашу текущую позицию; menu <имя> — DeluxeMenus.", NamedTextColor.GRAY));
    }

    private static Location placementInFront(Player player) {
        Location origin = player.getLocation();
        org.bukkit.util.Vector direction = origin.getDirection().setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            direction.setZ(1.0D);
        } else {
            direction.normalize();
        }
        Location result = origin.clone().add(direction.multiply(2.0D));
        // Face the NPC back toward the player while retaining a normal standing Y coordinate.
        result.setYaw(origin.getYaw() + 180.0F);
        result.setPitch(0.0F);
        return result;
    }

    private static String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    private static void success(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[PurpurBedrockNPC] " + text, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[PurpurBedrockNPC] " + text, NamedTextColor.RED));
    }

    private static void notFound(CommandSender sender, String id) {
        error(sender, "NPC '" + id + "' не найден.");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of("create", "remove", "movehere", "type", "holo", "holoheight", "action", "list", "info", "reload", "selftest"));
        }
        if (args.length == 2 && List.of("remove", "movehere", "type", "holo", "holoheight", "action", "info").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(args[1], manager.definitions().stream().map(npc -> npc.id).sorted().toList());
        }
        if (args.length == 3 && "create".equalsIgnoreCase(args[0])) {
            return filter(args[2], Stream.of(EntityType.values()).filter(NpcManager::isSupportedMob).map(type -> type.name().toLowerCase(Locale.ROOT)).toList());
        }
        if (args.length == 3 && "type".equalsIgnoreCase(args[0])) {
            return filter(args[2], Stream.of(EntityType.values()).filter(NpcManager::isSupportedMob).map(type -> type.name().toLowerCase(Locale.ROOT)).toList());
        }
        if (args.length == 3 && ("holo".equalsIgnoreCase(args[0]) || "hologram".equalsIgnoreCase(args[0]))) {
            return filter(args[2], List.of("set", "clear"));
        }
        if (args.length == 3 && "action".equalsIgnoreCase(args[0])) {
            return filter(args[2], List.of("add", "list", "remove", "clear"));
        }
        if (args.length == 4 && "action".equalsIgnoreCase(args[0]) && "add".equalsIgnoreCase(args[2])) {
            return filter(args[3], List.of("console", "player", "message", "menu", "teleport"));
        }
        return List.of();
    }

    private static List<String> filter(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                result.add(value);
            }
        }
        return result;
    }

    private record CollectionView(List<String> ids) {
    }
}
