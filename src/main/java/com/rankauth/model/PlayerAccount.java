package com.rankauth.model;

import java.util.UUID;

public final class PlayerAccount {
    public UUID uuid;
    public String username;
    public String passwordHash;
    public String email;
    public boolean emailVerified;
    public long registeredAt;
    public long lastLogin;
    public String lastIp;
    public long sessionExpiresAt;

    public PlayerAccount(UUID uuid, String username, String passwordHash, String email,
                          boolean emailVerified, long registeredAt, long lastLogin,
                          String lastIp, long sessionExpiresAt) {
        this.uuid = uuid;
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.emailVerified = emailVerified;
        this.registeredAt = registeredAt;
        this.lastLogin = lastLogin;
        this.lastIp = lastIp;
        this.sessionExpiresAt = sessionExpiresAt;
    }
}
