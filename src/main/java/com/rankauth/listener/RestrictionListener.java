package com.rankauth.listener;

import com.rankauth.auth.PlayerSession;
import com.rankauth.auth.SessionManager;
import com.rankauth.config.ConfigManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Locale;
import java.util.Set;

public final class RestrictionListener implements Listener {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "/login", "/register"
    );

    private final SessionManager sessions;
    private final ConfigManager config;

    public RestrictionListener(SessionManager sessions, ConfigManager config) {
        this.sessions = sessions;
        this.config = config;
    }

    private PlayerSession restrictedSession(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isRestricted()) return null;
        return session;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = restrictedSession(player);
        if (session == null) return;

        // Allow head rotation (looking around) but cancel actual positional movement,
        // and hard-clamp them back to the safe auth platform to guarantee no void fall.
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        boolean positionChanged = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        if (positionChanged) {
            Location safe = session.safeLocation != null ? session.safeLocation.clone() : from.clone();
            safe.setYaw(to.getYaw());
            safe.setPitch(to.getPitch());
            event.setTo(safe);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        PlayerSession session = restrictedSession(player);
        if (session == null) return;
        // Covers fall/void/all damage causes — unauthenticated players never take damage or die.
        event.setCancelled(true);
        if (player.getHealth() <= 0.0) {
            player.setHealth(Math.min(20.0, player.getMaxHealth()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = restrictedSession(player);
        if (session == null) return;

        String raw = event.getMessage().toLowerCase(Locale.ROOT).trim();
        String base = raw.split(" ")[0];
        if (!ALLOWED_COMMANDS.contains(base)) {
            event.setCancelled(true);
            boolean everRegistered = session.stage.name().equals("AWAITING_LOGIN");
            player.sendMessage(everRegistered ? config.message("need-login") : config.message("need-register"));
        }
    }
}
