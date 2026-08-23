package com.rankauth.command;

import com.rankauth.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class OpSistemiKaldirCommand implements CommandExecutor {

    private final AuthManager authManager;

    public OpSistemiKaldirCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rankauth.opsistemikaldir")) {
            sender.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkiniz yok.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Kullanım: /opsistemikaldir <oyuncu>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Oyuncu bulunamadı veya çevrimdışı.");
            return true;
        }
        authManager.removeOpIpLock(sender, target);
        return true;
    }
}
