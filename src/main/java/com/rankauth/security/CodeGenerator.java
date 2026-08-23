package com.rankauth.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

public final class CodeGenerator {

    // See BCrypt#nonBlockingRandom — plain `new SecureRandom()` can block for
    // a long time on entropy-starved hosts. Same fix applied here.
    private static final SecureRandom RANDOM = createRandom();

    private static SecureRandom createRandom() {
        try {
            return SecureRandom.getInstance("NativePRNGNonBlocking");
        } catch (Exception ignored) {
            return new SecureRandom();
        }
    }

    private CodeGenerator() {}

    public static String generateNumericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** SHA-256 hash of the code — stored in place of the plaintext code. */
    public static String hashCode(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean matches(String rawCode, String storedHash) {
        return hashCode(rawCode).equals(storedHash);
    }
}
