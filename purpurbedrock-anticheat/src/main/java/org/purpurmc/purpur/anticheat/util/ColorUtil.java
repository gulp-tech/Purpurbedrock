package org.purpurmc.purpur.anticheat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .hexCharacter('#')
        .build();

    private ColorUtil() {
    }

    public static Component color(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String normalized = raw.replace('§', '&');
        return SERIALIZER.deserialize(normalized);
    }

    public static Component itemText(String raw) {
        return color(raw).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> itemLore(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(itemText(line));
        }
        return out;
    }

    public static String legacySection(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher m = HEX_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder repl = new StringBuilder("§x");
            for (int i = 0; i < 6; i++) {
                repl.append('§').append(hex.charAt(i));
            }
            m.appendReplacement(sb, repl.toString());
        }
        m.appendTail(sb);
        return translateAmpersand(sb.toString());
    }

    private static String translateAmpersand(String input) {
        char[] b = input.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}
