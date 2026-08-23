package com.rankauth.command;

import com.rankauth.auth.AuthManager;
import com.rankauth.config.ConfigManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class LoginCommand implements CommandExecutor {

    private final AuthManager authManager;
    private final ConfigManager config;

    public LoginCommand(AuthManager authManager, ConfigManager config) {
        this.authManager = authManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut yalnızca oyuncular tarafından kullanılabilir.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(config.message("login-usage"));
            return true;
        }
        authManager.handleLoginCommand(player, args[0]);
        return true;
    }
}
