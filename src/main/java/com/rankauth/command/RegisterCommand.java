package com.rankauth.command;

import com.rankauth.auth.AuthManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RegisterCommand implements CommandExecutor {

    private final AuthManager authManager;

    public RegisterCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut yalnızca oyuncular tarafından kullanılabilir.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.YELLOW + "Kullanım: /register <şifre> <şifre>");
            return true;
        }
        authManager.handleRegisterCommand(player, args[0], args[1]);
        return true;
    }
}
