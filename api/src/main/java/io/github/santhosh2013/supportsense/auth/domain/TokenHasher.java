package io.github.santhosh2013.supportsense.auth.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Pure hashing helper — zero Spring, zero JPA. Refresh tokens are stored only as this hash,
 * never in plaintext, so a database leak does not expose usable tokens.
 */
public final class TokenHasher {

    private TokenHasher() {}

    public static String sha256(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM per the Java Cryptography spec.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
