package com.rankauth.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Central accessor for config.yml values. Resolves secrets (SMTP credentials,
 * DB password) from environment variables first, falling back to config.yml
 * only if the environment variable is not set.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
    }

    // ---- auth ----
    public int registrationTimeSeconds() {
        return cfg().getInt("auth.registration-time-seconds", 180);
    }

    public int loginTimeSeconds() {
        return cfg().getInt("auth.login-time-seconds", 180);
    }

    public int minimumPasswordLength() {
        return cfg().getInt("auth.minimum-password-length", 10);
    }

    public boolean requireUppercase() {
        return cfg().getBoolean("auth.require-uppercase", true);
    }

    public boolean requireLowercase() {
        return cfg().getBoolean("auth.require-lowercase", true);
    }

    public boolean requireNumber() {
        return cfg().getBoolean("auth.require-number", true);
    }

    public int maxFailedLoginAttempts() {
        return cfg().getInt("auth.max-failed-login-attempts", 5);
    }

    public int failedLoginLockoutSeconds() {
        return cfg().getInt("auth.failed-login-lockout-seconds", 60);
    }

    // ---- verification ----
    public int verificationCodeLength() {
        return cfg().getInt("verification.code-length", 6);
    }

    public int verificationExpirationSeconds() {
        return cfg().getInt("verification.expiration-seconds", 300);
    }

    // ---- database ----
    public String databaseType() {
        return cfg().getString("database.type", "sqlite");
    }

    public String mysqlHost() {
        return cfg().getString("database.mysql.host", "localhost");
    }

    public int mysqlPort() {
        return cfg().getInt("database.mysql.port", 3306);
    }

    public String mysqlDatabase() {
        return cfg().getString("database.mysql.database", "rankauth");
    }

    public String mysqlUsername() {
        return cfg().getString("database.mysql.username", "rankauth");
    }

    public String mysqlPassword() {
        String env = System.getenv("RANKAUTH_DB_PASSWORD");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return cfg().getString("database.mysql.password", "");
    }

    public boolean mysqlUseSsl() {
        return cfg().getBoolean("database.mysql.use-ssl", false);
    }

    // ---- smtp ----
    public String smtpHost() {
        return cfg().getString("smtp.host", "smtp.example.com");
    }

    public int smtpPort() {
        return cfg().getInt("smtp.port", 587);
    }

    public boolean smtpStartTls() {
        return cfg().getBoolean("smtp.starttls", true);
    }

    public String smtpUsername() {
        String env = System.getenv("RANKAUTH_SMTP_USERNAME");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return cfg().getString("smtp.username", "");
    }

    public String smtpPassword() {
        String env = System.getenv("RANKAUTH_SMTP_PASSWORD");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return cfg().getString("smtp.password", "");
    }

    public String smtpFromAddress() {
        return cfg().getString("smtp.from-address", "no-reply@example.com");
    }

    public String smtpFromName() {
        return cfg().getString("smtp.from-name", "RankAuth");
    }

    public String smtpSubject() {
        return cfg().getString("smtp.subject", "Doğrulama Kodu");
    }

    // ---- hub ----
    public String hubCommand() {
        return cfg().getString("hub.command", "hub");
    }

    // ---- op-security ----
    public boolean opSecurityEnabled() {
        return cfg().getBoolean("op-security.enabled", true);
    }

    public boolean behindProxy() {
        return cfg().getBoolean("op-security.behind-proxy", false);
    }

    // ---- session ----
    public boolean sessionEnabled() {
        return cfg().getBoolean("session.enabled", true);
    }

    public int sessionDurationSeconds() {
        return cfg().getInt("session.duration", 60);
    }

    public String messagePrefix() {
        return com.rankauth.util.ColorUtil.translate(cfg().getString("messages.prefix", "Silvera"));
    }

    // ---- messages ----
    public String message(String key) {
        String raw = cfg().getString("messages." + key, key);
        return com.rankauth.util.ColorUtil.translate(raw);
    }

    // ---- welcome / info screen (shown on first join, while unregistered) ----
    public java.util.List<String> welcomeLines() {
        java.util.List<String> raw = cfg().getStringList("welcome.lines");
        java.util.List<String> translated = new java.util.ArrayList<>();
        for (String line : raw) {
            translated.add(com.rankauth.util.ColorUtil.translate(line));
        }
        return translated;
    }

    // ---- ambient auth music ----
    public boolean musicEnabled() {
        return cfg().getBoolean("music.enabled", true);
    }

    public String musicSound() {
        return cfg().getString("music.sound", "minecraft:music_disc.cat");
    }

    public float musicVolume() {
        return (float) cfg().getDouble("music.volume", 0.6);
    }

    public float musicPitch() {
        return (float) cfg().getDouble("music.pitch", 1.0);
    }

    public int musicLoopSeconds() {
        return cfg().getInt("music.loop-seconds", 185);
    }
}
