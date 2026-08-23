package com.rankauth.command;

import com.rankauth.auth.AuthManager;
import com.rankauth.config.ConfigManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /register <şifre> <email> — şifreyi doğrular, hash'ler, e-postaya kod gönderir. */
public final class RegisterCommand implements CommandExecutor {

    private final AuthManager authManager;
    private final ConfigManager config;

    public RegisterCommand(AuthManager authManager, ConfigManager config) {
        this.authManager = authManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut yalnızca oyuncular tarafından kullanılabilir.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(config.message("register-usage"));
            return true;
        }
        authManager.handleRegisterCommand(player, args[0], args[1]);
        return true;
    }
}
