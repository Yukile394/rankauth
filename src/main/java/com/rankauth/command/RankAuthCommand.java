package com.rankauth.command;

import com.rankauth.config.ConfigManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class RankAuthCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final ConfigManager config;

    public RankAuthCommand(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rankauth.reload")) {
            sender.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkiniz yok.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            config.reload();
            sender.sendMessage(ChatColor.GREEN + "RankAuth config yeniden yüklendi.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Kullanım: /rankauth reload");
        return true;
    }
}
