package com.rankauth.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates both legacy '&' color codes and hex codes written as
 * '&#RRGGBB' into the format Bukkit's legacy chat renders correctly.
 */
public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

    private ColorUtil() {
    }

    /**
     * Translates '&' / hex color codes and returns a real Adventure Component,
     * so titles/kick screens actually render the colors instead of showing
     * literal color-code characters (which display as plain/uncolored text).
     */
    public static net.kyori.adventure.text.Component component(String input) {
        if (input == null) return net.kyori.adventure.text.Component.empty();
        return LEGACY.deserialize(translate(input));
    }

    public static String translate(String input) {
        if (input == null) return null;
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
