package com.rankauth.security;

import com.rankauth.config.ConfigManager;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing via PBKDF2WithHmacSHA256 (JDK built-in, no vendored crypto
 * code, no external dependency). Replaces a previous hand-vendored BCrypt
 * implementation whose S-box table was truncated to 256 entries instead of
 * the required 1024, causing ArrayIndexOutOfBoundsException on some inputs.
 *
 * Stored format: pbkdf2:<iterations>:<base64 salt>:<base64 hash>
 */
public final class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private PasswordUtil() {}

    private static SecureRandom nonBlockingRandom() {
        try {
            return SecureRandom.getInstance("NativePRNGNonBlocking");
        } catch (Exception ignored) {
            return new SecureRandom();
        }
    }

    public static String hash(String plaintext) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        nonBlockingRandom().nextBytes(salt);
        byte[] hash = pbkdf2(plaintext.toCharArray(), salt, ITERATIONS);
        return "pbkdf2:" + ITERATIONS + ":" +
                Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(hash);
    }

    public static boolean matches(String plaintext, String stored) {
        if (stored == null) return false;
        try {
            String[] parts = stored.split(":");
            if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(plaintext.toCharArray(), salt, iterations);
            if (actual.length != expected.length) return false;
            int result = 0;
            for (int i = 0; i < actual.length; i++) {
                result |= actual[i] ^ expected[i];
            }
            return result == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
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
