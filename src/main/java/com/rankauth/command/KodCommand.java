package com.rankauth.command;

import com.rankauth.auth.AuthManager;
import com.rankauth.config.ConfigManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /kod <kod> — e-postaya gelen doğrulama kodunu girer, kaydı tamamlar. */
public final class KodCommand implements CommandExecutor {

    private final AuthManager authManager;
    private final ConfigManager config;

    public KodCommand(AuthManager authManager, ConfigManager config) {
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
            player.sendMessage(config.message("kod-usage"));
            return true;
        }
        authManager.handleKodCommand(player, args[0]);
        return true;
    }
}
