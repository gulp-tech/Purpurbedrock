package org.purpurmc.purpur.bedrock.menu;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.purpurmc.purpur.PurpurConfig;
import org.slf4j.Logger;

/**
 * Built-in Java-style Chest GUI engine (DeluxeMenus YAML format).
 * Renders genuine Java Edition Chest Inventories (never Bedrock Forms)
 * and automatically yields command registration if a Java plugin (e.g. DeluxeMenus)
 * already registered the same command.
 */
public final class JavaChestMenuManager {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Pattern HEX_AMP_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Map<String, MenuDefinition> MENUS = new LinkedHashMap<>();
    private static final List<String> REGISTERED_DYNAMIC_COMMANDS = new ArrayList<>();

    private record MenuItemDef(
        Material material,
        int amount,
        boolean glow,
        String displayName,
        List<String> lore,
        List<Integer> slots,
        List<String> clickCommands,
        List<String> leftClickCommands,
        List<String> rightClickCommands
    ) {
    }

    private record MenuDefinition(
        String id,
        String title,
        int size,
        List<String> openCommands,
        String permission,
        List<MenuItemDef> items
    ) {
    }

    private JavaChestMenuManager() {
    }

    public static int getLoadedMenuCount() {
        return MENUS.size();
    }

    public static List<String> getMenuIds() {
        return List.copyOf(MENUS.keySet());
    }

    public static synchronized void initAfterPlugins() {
        MENUS.clear();
        if (!PurpurConfig.bedrockBuiltInMenusEnabled) {
            return;
        }

        File file = new File(PurpurConfig.bedrockMenusFile);
        if (!file.exists()) {
            writeDefaultMenusFile(file);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("menus");
        if (root == null) {
            root = yaml;
        }

        for (String menuId : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(menuId);
            if (sec == null) {
                continue;
            }
            MenuDefinition def = parseMenu(menuId, sec);
            if (def != null) {
                MENUS.put(menuId.toLowerCase(Locale.ROOT), def);
            }
        }

        registerMenuCommands();
        LOGGER.info("[Purpurbedrock] Loaded {} Java-style chest menu(s) from {}.", MENUS.size(), file.getName());
    }

    private static void registerMenuCommands() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.server == null) {
            return;
        }
        CommandMap commandMap = server.server.getCommandMap();

        for (MenuDefinition def : MENUS.values()) {
            for (String cmdName : def.openCommands()) {
                String normalized = cmdName.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    continue;
                }
                Command existing = commandMap.getCommand(normalized);
                if (existing != null && !(existing instanceof BuiltInMenuCommand)) {
                    LOGGER.info("[Purpurbedrock] Command '/{}' is already provided by a plugin ({}), keeping plugin command.", normalized, existing.getClass().getSimpleName());
                    continue;
                }
                if (existing == null) {
                    BuiltInMenuCommand cmd = new BuiltInMenuCommand(normalized, def.id());
                    if (def.permission() != null && !def.permission().isBlank()) {
                        cmd.setPermission(def.permission());
                    }
                    commandMap.register("purpurbedrock", cmd);
                    REGISTERED_DYNAMIC_COMMANDS.add(normalized);
                } else if (existing instanceof BuiltInMenuCommand builtIn) {
                    builtIn.setMenuId(def.id());
                }
            }
        }
    }

    private static MenuDefinition parseMenu(final String id, final ConfigurationSection sec) {
        String title = sec.getString("menu_title", sec.getString("title", "&8Меню сервера"));
        int rawSize = sec.getInt("size", 54);
        int size = Math.clamp(((rawSize + 8) / 9) * 9, 9, 54);
        String permission = sec.getString("permission", "");

        List<String> openCommands = new ArrayList<>();
        if (sec.isList("open_command")) {
            for (Object o : sec.getList("open_command", Collections.emptyList())) {
                if (o != null) {
                    openCommands.add(o.toString());
                }
            }
        } else {
            String single = sec.getString("open_command", "");
            if (!single.isBlank()) {
                openCommands.add(single);
            }
        }

        List<MenuItemDef> items = new ArrayList<>();
        ConfigurationSection itemsSec = sec.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String itemKey : itemsSec.getKeys(false)) {
                ConfigurationSection itemSec = itemsSec.getConfigurationSection(itemKey);
                if (itemSec == null) {
                    continue;
                }
                String matName = itemSec.getString("material", "STONE");
                Material mat = Material.matchMaterial(matName);
                if (mat == null || mat.isAir()) {
                    LOGGER.warn("[Purpurbedrock] Unknown material '{}' in menu '{}', item '{}'; falling back to STONE.", matName, id, itemKey);
                    mat = Material.STONE;
                }
                int amount = Math.clamp(itemSec.getInt("amount", 1), 1, 64);
                boolean glow = itemSec.getBoolean("glow", false);
                String displayName = itemSec.getString("display_name", itemSec.getString("name", null));
                List<String> lore = itemSec.getStringList("lore");
                List<Integer> slots = parseSlots(itemSec, size);
                List<String> clickCommands = itemSec.getStringList("click_commands");
                List<String> leftCommands = itemSec.getStringList("left_click_commands");
                List<String> rightCommands = itemSec.getStringList("right_click_commands");

                items.add(new MenuItemDef(mat, amount, glow, displayName, lore, slots, clickCommands, leftCommands, rightCommands));
            }
        }

        return new MenuDefinition(id, title, size, openCommands, permission, items);
    }

    private static List<Integer> parseSlots(final ConfigurationSection itemSec, final int menuSize) {
        List<Integer> result = new ArrayList<>();
        if (itemSec.contains("slot")) {
            int s = itemSec.getInt("slot", -1);
            if (s >= 0 && s < menuSize) {
                result.add(s);
            }
        }
        if (itemSec.isList("slots")) {
            for (Object entry : itemSec.getList("slots", Collections.emptyList())) {
                if (entry instanceof Number num) {
                    int s = num.intValue();
                    if (s >= 0 && s < menuSize) {
                        result.add(s);
                    }
                } else if (entry != null) {
                    String str = entry.toString().trim();
                    int dash = str.indexOf('-');
                    if (dash > 0) {
                        try {
                            int start = Integer.parseInt(str.substring(0, dash).trim());
                            int end = Integer.parseInt(str.substring(dash + 1).trim());
                            for (int s = Math.max(0, start); s <= Math.min(menuSize - 1, end); s++) {
                                result.add(s);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    } else {
                        try {
                            int s = Integer.parseInt(str);
                            if (s >= 0 && s < menuSize) {
                                result.add(s);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        return result;
    }

    public static boolean openMenu(final Player player, final String menuId) {
        MenuDefinition def = MENUS.get(menuId.toLowerCase(Locale.ROOT));
        if (def == null) {
            return false;
        }
        if (def.permission() != null && !def.permission().isBlank() && !player.hasPermission(def.permission())) {
            player.sendMessage(parseText(player, "&cУ вас нет прав для открытия этого меню."));
            return true;
        }

        JavaChestMenuHolder holder = new JavaChestMenuHolder(def.id());
        Inventory inv = Bukkit.createInventory(holder, def.size(), parseText(player, def.title()));
        holder.setInventory(inv);

        for (MenuItemDef itemDef : def.items()) {
            ItemStack stack = new ItemStack(itemDef.material(), itemDef.amount());
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (itemDef.displayName() != null) {
                    meta.displayName(parseText(player, itemDef.displayName()).decoration(TextDecoration.ITALIC, false));
                }
                if (itemDef.lore() != null && !itemDef.lore().isEmpty()) {
                    List<Component> loreComponents = new ArrayList<>(itemDef.lore().size());
                    for (String line : itemDef.lore()) {
                        loreComponents.add(parseText(player, line).decoration(TextDecoration.ITALIC, false));
                    }
                    meta.lore(loreComponents);
                }
                if (itemDef.glow()) {
                    meta.setEnchantmentGlintOverride(true);
                }
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                stack.setItemMeta(meta);
            }

            JavaChestMenuHolder.SlotActions slotActions = new JavaChestMenuHolder.SlotActions(
                itemDef.clickCommands(),
                itemDef.leftClickCommands(),
                itemDef.rightClickCommands()
            );
            for (int slot : itemDef.slots()) {
                inv.setItem(slot, stack);
                holder.putActions(slot, slotActions);
            }
        }

        player.openInventory(inv);
        return true;
    }

    public static void handleMenuClick(final ServerPlayer nmsPlayer, final InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof JavaChestMenuHolder holder)) {
            return;
        }
        // Always cancel item movement in Java Chest GUI menus
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }

        JavaChestMenuHolder.SlotActions actions = holder.getActions(rawSlot);
        if (actions == null) {
            return;
        }

        Player player = nmsPlayer.getBukkitEntity();
        List<String> toRun = new ArrayList<>(actions.anyClickCommands());
        ClickType click = event.getClick();
        if (click.isRightClick() && !actions.rightClickCommands().isEmpty()) {
            toRun.addAll(actions.rightClickCommands());
        } else if (click.isLeftClick() && !actions.leftClickCommands().isEmpty()) {
            toRun.addAll(actions.leftClickCommands());
        } else if (!actions.leftClickCommands().isEmpty() && actions.rightClickCommands().isEmpty()) {
            // On touch Bedrock screens a tap registers as LEFT click; fallback cleanly
            toRun.addAll(actions.leftClickCommands());
        }

        if (toRun.isEmpty()) {
            return;
        }

        // Execute commands safely on the main thread after click processing
        MinecraftServer.getServer().execute(() -> executeActions(player, toRun));
    }

    private static void executeActions(final Player player, final List<String> commands) {
        if (!player.isOnline()) {
            return;
        }
        for (String raw : commands) {
            String line = applyPlaceholders(player, raw.trim());
            if (line.equalsIgnoreCase("[close]")) {
                player.closeInventory();
            } else if (line.regionMatches(true, 0, "[player]", 0, 8)) {
                String cmd = stripLeadingSlash(line.substring(8).trim());
                if (!cmd.isEmpty()) {
                    player.performCommand(cmd);
                }
            } else if (line.regionMatches(true, 0, "[console]", 0, 9)) {
                String cmd = stripLeadingSlash(line.substring(9).trim());
                if (!cmd.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            } else if (line.regionMatches(true, 0, "[message]", 0, 9)) {
                String msg = line.substring(9).trim();
                player.sendMessage(parseText(player, msg));
            } else if (line.regionMatches(true, 0, "[openguimenu]", 0, 13)) {
                String targetMenu = line.substring(13).trim();
                openMenu(player, targetMenu);
            } else if (line.regionMatches(true, 0, "[sound]", 0, 7)) {
                playMenuSound(player, line.substring(7).trim());
            } else if (!line.isEmpty()) {
                player.performCommand(stripLeadingSlash(line));
            }
        }
    }

    private static void playMenuSound(final Player player, final String spec) {
        if (spec.isEmpty()) {
            return;
        }
        String[] parts = spec.split("\\s+");
        String rawSound = parts[0].toLowerCase(Locale.ROOT);
        float volume = 1.0F;
        float pitch = 1.0F;
        if (parts.length >= 2) {
            try {
                volume = Float.parseFloat(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (parts.length >= 3) {
            try {
                pitch = Float.parseFloat(parts[2]);
            } catch (NumberFormatException ignored) {
            }
        }

        NamespacedKey key = rawSound.contains(":")
            ? NamespacedKey.fromString(rawSound)
            : NamespacedKey.minecraft(rawSound);
        Sound sound = key != null ? Registry.SOUNDS.get(key) : null;
        if (sound == null && !rawSound.contains(":")) {
            NamespacedKey dotKey = NamespacedKey.minecraft(rawSound.replace('_', '.'));
            sound = Registry.SOUNDS.get(dotKey);
        }
        if (sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private static String stripLeadingSlash(final String cmd) {
        return cmd.startsWith("/") ? cmd.substring(1) : cmd;
    }

    private static Component parseText(final Player player, final String input) {
        String text = applyPlaceholders(player, input);
        if (text.contains("&") || text.contains("§")) {
            Matcher matcher = HEX_AMP_PATTERN.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb, "&x"
                    + "&" + matcher.group(1).charAt(0)
                    + "&" + matcher.group(1).charAt(1)
                    + "&" + matcher.group(1).charAt(2)
                    + "&" + matcher.group(1).charAt(3)
                    + "&" + matcher.group(1).charAt(4)
                    + "&" + matcher.group(1).charAt(5));
            }
            matcher.appendTail(sb);
            return LegacyComponentSerializer.legacyAmpersand().deserialize(sb.toString().replace('§', '&'));
        }
        return MiniMessage.miniMessage().deserialize(text);
    }

    private static String applyPlaceholders(final Player player, final String text) {
        Location loc = player.getLocation();
        String result = text
            .replace("%player_name%", player.getName())
            .replace("%player%", player.getName())
            .replace("%online%", Integer.toString(Bukkit.getOnlinePlayers().size()))
            .replace("%max_players%", Integer.toString(Bukkit.getMaxPlayers()))
            .replace("%ping%", Integer.toString(player.getPing()))
            .replace("%world%", player.getWorld().getName())
            .replace("%x%", Integer.toString(loc.getBlockX()))
            .replace("%y%", Integer.toString(loc.getBlockY()))
            .replace("%z%", Integer.toString(loc.getBlockZ()));

        // Optional PlaceholderAPI integration if installed in plugins/
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Method setPlaceholders = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
                Object out = setPlaceholders.invoke(null, player, result);
                if (out instanceof String str) {
                    result = str;
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static void writeDefaultMenusFile(final File file) {
        String content = """
            # ============================================================
            # Purpurbedrock — Меню в стиле Java Edition (Chest GUI)
            # Формат полностью совместим с синтаксисом DeluxeMenus!
            # Если вы установите плагин DeluxeMenus в папку plugins/, его команды
            # автоматически получат приоритет без конфликтов.
            # ============================================================

            menus:
              main:
                menu_title: "&8✦ &bМеню сервера &8✦"
                open_command:
                  - menu
                size: 54
                items:
                  border:
                    material: GRAY_STAINED_GLASS_PANE
                    display_name: " "
                    slots:
                      - "0-8"
                      - 9
                      - 17
                      - 18
                      - 26
                      - 27
                      - 35
                      - 36
                      - "44-53"
                  profile:
                    material: NETHER_STAR
                    slot: 13
                    glow: true
                    display_name: "&b✦ &fПрофиль: &e%player_name%"
                    lore:
                      - "&7Онлайн сервера: &a%online%&8/&7%max_players%"
                      - "&7Ваш пинг: &b%ping% мс"
                      - "&7Мир: &f%world% &8(%x%, %y%, %z%)"
                      - ""
                      - "&eНажмите, чтобы обновить меню"
                    click_commands:
                      - "[sound] ui.button.click 1.0 1.2"
                      - "[openguimenu] main"
                  spawn:
                    material: ENDER_PEARL
                    slot: 21
                    display_name: "&aТелепортация на спавн"
                    lore:
                      - "&7Быстрое перемещение в лобби/спавн"
                      - ""
                      - "&a▶ Нажмите для перехода"
                    click_commands:
                      - "[sound] entity.enderman.teleport 1.0 1.0"
                      - "[close]"
                      - "[player] spawn"
                  combat_info:
                    material: DIAMOND_SWORD
                    slot: 22
                    glow: true
                    display_name: "&c⚔ Боёвка Bedrock Edition"
                    lore:
                      - "&7На сервере активна нативная боёвка Bedrock:"
                      - "&8• &fУдары без перезарядки 1.9"
                      - "&8• &fКриты в спринте и прыжке"
                      - "&8• &fМгновенный щит на Shift (присед)"
                      - "&8• &fОткидывание снежками и яйцами"
                      - ""
                      - "&e▶ Нажмите для подробностей"
                    click_commands:
                      - "[sound] ui.button.click 1.0 1.0"
                      - "[openguimenu] info"
                  tps_info:
                    material: COMPASS
                    slot: 23
                    display_name: "&dИндикатор TPS / Навигатор"
                    lore:
                      - "&7Переключить верхнюю панель компаса"
                      - ""
                      - "&d▶ Нажмите для переключения"
                    click_commands:
                      - "[sound] ui.button.click 1.0 1.1"
                      - "[close]"
                      - "[player] compass"
                  close:
                    material: BARRIER
                    slot: 49
                    display_name: "&cЗакрыть меню"
                    click_commands:
                      - "[sound] ui.button.click 1.0 0.8"
                      - "[close]"

              info:
                menu_title: "&8✦ &dО сервере Purpurbedrock &8✦"
                size: 27
                items:
                  filler:
                    material: BLACK_STAINED_GLASS_PANE
                    display_name: " "
                    slots:
                      - "0-26"
                  details:
                    material: BOOK
                    slot: 13
                    glow: true
                    display_name: "&bPurpurbedrock 26.3"
                    lore:
                      - "&7Вход: &aТолько Minecraft: Bedrock Edition"
                      - "&7Плагины: &aПолная поддержка Java-плагинов"
                      - "&7Меню: &aИнвентарные GUI как на Java"
                      - "&7Боёвка: &aНативная механика Bedrock"
                  back:
                    material: ARROW
                    slot: 22
                    display_name: "&e◀ Назад в главное меню"
                    click_commands:
                      - "[sound] ui.button.click 1.0 1.0"
                      - "[openguimenu] main"
            """;
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.error("[Purpurbedrock] Could not write default {}", file.getName(), ex);
        }
    }

    private static final class BuiltInMenuCommand extends Command {
        private volatile String menuId;

        BuiltInMenuCommand(final String commandName, final String menuId) {
            super(commandName);
            this.menuId = menuId;
            this.description = "Opens Purpurbedrock Java-style chest menu";
            this.usageMessage = "/" + commandName;
        }

        void setMenuId(final String menuId) {
            this.menuId = menuId;
        }

        @Override
        public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Эта команда доступна только игрокам.");
                return true;
            }
            if (!openMenu(player, this.menuId)) {
                player.sendMessage("§cМеню '" + this.menuId + "' сейчас недоступно.");
            }
            return true;
        }
    }
}
