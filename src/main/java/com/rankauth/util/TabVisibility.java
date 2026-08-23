package com.rankauth.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * While a player is unauthenticated they are hidden from every other online
 * player's player-list/entity view, and every other player is hidden from
 * them. This satisfies the "cannot use Tab / player list" requirement without
 * touching chat history or any other plugin's UI.
 */
public final class TabVisibility {

    private TabVisibility() {}

    public static void hideFromEveryone(Plugin plugin, Player restricted) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(restricted)) continue;
            other.hidePlayer(plugin, restricted);
            restricted.hidePlayer(plugin, other);
        }
    }

    public static void restoreVisibility(Plugin plugin, Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) continue;
            other.showPlayer(plugin, player);
            player.showPlayer(plugin, other);
        }
    }
}
