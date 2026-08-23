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
            plugin.getLogger().info("HubShat bulunamadi — kayit/giris sonrasi oyuncular spawn'a teleport edilecek.");
        }
    }

    public void sendToHub(Player player) {
        if (hubShatPresent) {
            String command = config.hubCommand();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getServer().dispatchCommand(player, command));
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.teleport(player.getWorld().getSpawnLocation());
                }
            });
        }
    }

    public boolean isHubShatPresent() {
        return hubShatPresent;
    }
}
