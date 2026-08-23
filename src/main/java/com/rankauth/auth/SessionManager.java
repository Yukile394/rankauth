package com.rankauth.auth;

import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public PlayerSession getOrCreate(UUID uuid, AuthStage initialStage) {
        return sessions.computeIfAbsent(uuid, u -> new PlayerSession(u, initialStage));
    }

    public PlayerSession get(UUID uuid) {
        return sessions.get(uuid);
    }

    public boolean isAuthenticated(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        return s != null && s.stage == AuthStage.AUTHENTICATED;
    }

    public void remove(UUID uuid) {
        PlayerSession s = sessions.remove(uuid);
        if (s != null) {
            cancelTasks(s);
        }
    }

    public void cancelTasks(PlayerSession session) {
        if (session.timeoutTask != null) {
            session.timeoutTask.cancel();
            session.timeoutTask = null;
        }
        if (session.bossBarTask != null) {
            session.bossBarTask.cancel();
            session.bossBarTask = null;
        }
        if (session.bossBar instanceof BossBar bossBar) {
            bossBar.removeAll();
        }
    }

    public void cancelAll() {
        sessions.values().forEach(this::cancelTasks);
        sessions.clear();
    }
}
