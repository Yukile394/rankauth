package com.rankauth.hub;

import com.rankauth.config.ConfigManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class HubIntegration {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final boolean hubShatPresent;

    public HubIntegration(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        Plugin hubShat = plugin.getServer().getPluginManager().getPlugin("HubShat");
        this.hubShatPresent = hubShat != null && hubShat.isEnabled();
        if (!hubShatPresent) {
            plugin.getLogger().warning("HubShat not found on this server — players will not be " +
                    "auto-teleported to Hub after registration. Falling back to no-op (safe default).");
        }
    }

    /** Sends the player to Hub immediately after registration completes. Safe no-op if HubShat is absent. */
    public void sendToHub(Player player) {
        if (!hubShatPresent) {
            return; // Logged once at startup; avoid spamming per-player.
        }
        String command = config.hubCommand();
        // Dispatch as the player so HubShat's own permission handling applies normally.
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getServer().dispatchCommand(player, command));
    }

    public boolean isHubShatPresent() {
        return hubShatPresent;
    }
}
