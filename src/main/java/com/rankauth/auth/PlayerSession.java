package com.rankauth.auth;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class PlayerSession {

    public final UUID uuid;
    public volatile AuthStage stage;
    public volatile Location safeLocation;

    // Registration flow scratch state
    public volatile String pendingPasswordHash; // set after first password entry, awaiting confirm
    public volatile String pendingEmail;

    // Timer state
    public volatile BukkitTask timeoutTask;
    public volatile BukkitTask bossBarTask;
    public volatile Object bossBar; // org.bukkit.boss.BossBar, kept as Object to avoid import cycles here
    public volatile int secondsRemaining;

    // Anti-fall protection: barrier block placed beneath safeLocation while
    // unauthenticated, and the original block type there so it can be restored.
    public volatile Location barrierLocation;
    public volatile org.bukkit.Material barrierPreviousType;

    // Calming ambient music looped in the background until authentication completes.
    public volatile BukkitTask musicTask;

    // Failed login rate limiting
    public volatile int failedAttempts;
    public volatile long lockedUntil;

    public PlayerSession(UUID uuid, AuthStage stage) {
        this.uuid = uuid;
        this.stage = stage;
    }

    public boolean isRestricted() {
        return stage != AuthStage.AUTHENTICATED;
    }
}
