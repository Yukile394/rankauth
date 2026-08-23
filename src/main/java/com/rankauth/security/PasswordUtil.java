package com.rankauth.security;

import com.rankauth.config.ConfigManager;

public final class PasswordUtil {

    private PasswordUtil() {}

    public static String hash(String plaintext) {
        return BCrypt.hashpw(plaintext, BCrypt.gensalt());
    }

    public static boolean matches(String plaintext, String hash) {
        return BCrypt.checkpw(plaintext, hash);
    }

    /**
     * Returns null if the password satisfies the configured policy, otherwise
     * returns the reason it does not (for logging/decisions only — the
     * player-facing message stays generic per config).
     */
    public static String validate(String password, ConfigManager cfg) {
        if (password.length() < cfg.minimumPasswordLength()) {
            return "too-short";
        }
        if (cfg.requireUppercase() && password.chars().noneMatch(Character::isUpperCase)) {
            return "no-uppercase";
        }
        if (cfg.requireLowercase() && password.chars().noneMatch(Character::isLowerCase)) {
            return "no-lowercase";
        }
        if (cfg.requireNumber() && password.chars().noneMatch(Character::isDigit)) {
            return "no-number";
        }
        return null;
    }
}
