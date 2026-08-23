package com.rankauth.listener;

import com.rankauth.auth.AuthManager;
import com.rankauth.auth.PlayerSession;
import com.rankauth.auth.SessionManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Intercepts chat for restricted players. Their own chat input is consumed by
 * the auth flow (password/email/code entry) and never broadcast. Other
 * players' normal chat messages are completely untouched — this listener only
 * ever cancels the sender's own event, so nobody's chat history is altered or
 * hidden from a restricted player's screen.
 */
public final class ChatListener implements Listener {

    private final JavaPlugin plugin;
    private final AuthManager authManager;
    private final SessionManager sessions;

    public ChatListener(JavaPlugin plugin, AuthManager authManager, SessionManager sessions) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isRestricted()) {
            return; // fully authenticated — normal chat behavior, untouched.
        }

        // Restricted player: consume their message as auth input, never broadcast it.
        event.setCancelled(true);
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        // AsyncChatEvent fires off the main thread. Everything downstream (session
        // mutation, BCrypt hashing, sendMessage, DB calls that hop back to the main
        // thread themselves) is safer and deterministic on the main thread, and any
        // exception thrown here previously vanished into the console with the player
        // seeing nothing at all. Hop to main thread and log failures loudly.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            try {
                authManager.handleChatInput(player, plainMessage);
            } catch (Exception ex) {
                plugin.getLogger().severe("Auth chat input failed for " + player.getName() + ": " + ex);
                ex.printStackTrace();
            }
        });
    }
}
