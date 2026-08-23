package com.rankauth.listener;

import com.rankauth.auth.AuthManager;
import com.rankauth.auth.PlayerSession;
import com.rankauth.auth.SessionManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Intercepts chat for restricted players. Their own chat input is consumed by
 * the auth flow (password/email/code entry) and never broadcast. Other
 * players' normal chat messages are completely untouched — this listener only
 * ever cancels the sender's own event, so nobody's chat history is altered or
 * hidden from a restricted player's screen.
 */
public final class ChatListener implements Listener {

    private final AuthManager authManager;
    private final SessionManager sessions;

    public ChatListener(AuthManager authManager, SessionManager sessions) {
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
        authManager.handleChatInput(player, plainMessage);
    }
}
