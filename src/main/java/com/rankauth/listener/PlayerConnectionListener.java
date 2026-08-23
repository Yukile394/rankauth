package com.rankauth.listener;

import com.rankauth.auth.AuthManager;
import com.rankauth.auth.SessionManager;
import com.rankauth.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerConnectionListener implements Listener {

    private final JavaPlugin plugin;
    private final AuthManager authManager;
    private final SessionManager sessions;
    private final ConfigManager config;

    public PlayerConnectionListener(JavaPlugin plugin, AuthManager authManager, SessionManager sessions, ConfigManager config) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.sessions = sessions;
        this.config = config;
    }

    /**
     * Rejects a second connection under the same (case-insensitive) username while
     * one is already active. Runs synchronously before the join completes, so the
     * existing connection is never touched — only the incoming duplicate is denied.
     * Checked by UUID first (the authoritative identity), then by name as a
     * defense-in-depth fallback for offline-mode-style name collisions.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        Player incoming = event.getPlayer();
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean sameUuid = online.getUniqueId().equals(incoming.getUniqueId());
            boolean sameName = online.getName().equalsIgnoreCase(incoming.getName());
            if (sameUuid || sameName) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                        net.kyori.adventure.text.Component.text(
                                "[" + config.messagePrefix() + "] " + config.message("duplicate-connection")));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay one tick so the player is fully placed in the world before we
        // teleport/query, avoiding races with other plugins on join.
        Bukkit.getScheduler().runTask(plugin, () -> authManager.beginAuthFlow(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Registration/login state is transient — cancel timers/bossbars, but the
        // persisted account row (including the short-lived trusted session fields
        // written at login) is untouched. Whether a returning player must /login
        // again is decided entirely by AuthManager#beginAuthFlow on next join.
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
