package io.github.santhosh2013.supportsense.auth.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRole;
import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Code-review Should-fix #4: neither review actually proved algorithm pinning with a test —
 * both reasoned from JJWT API semantics. This forges real attacker-controlled tokens and
 * feeds them to the actual parser, rather than asserting on library documentation.
 */
class JwtAlgorithmSecurityTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hmac-sha";

    private final SupportSenseProperties properties = new SupportSenseProperties(
            new SupportSenseProperties.Security(SECRET, "admin-pw", Duration.ofMinutes(15), Duration.ofDays(7)),
            new SupportSenseProperties.Ingestion(
                    4, 8, 500, Duration.ofMinutes(15), 3, Duration.ofHours(1), "customer-success"),
            new SupportSenseProperties.PreScreen(List.of("refund")));

    private final JwtTokenService jwtTokenService = new JwtTokenService(properties, Instant::now);

    @Test
    @DisplayName("a token with alg:none and no signature is rejected")
    void algNoneTokenIsRejected() {
        // Hand-crafted per RFC 7519/8725 "none" attack: header {"alg":"none"}, a valid-
        // looking payload, and an EMPTY signature segment — the classic bypass that skips
        // signature verification entirely on parsers that trust the header's alg field.
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"attacker@example.com\",\"role\":\"ADMIN\"}");
        String forgedToken = header + "." + payload + ".";

        assertThatThrownBy(() -> jwtTokenService.parseClaims(forgedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token signed with a different key than the server's is rejected")
    void tokenSignedWithWrongKeyIsRejected() {
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "a-completely-different-secret-the-server-never-issued".getBytes(StandardCharsets.UTF_8));

        String forgedToken = Jwts.builder()
                .subject("attacker@example.com")
                .claim("role", "ADMIN")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .signWith(attackerKey)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseClaims(forgedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token with an invalid/truncated signature is rejected")
    void tokenWithTamperedSignatureIsRejected() {
        User user = new User("user@example.com", "hash", "User", UserRole.AGENT, null);
        String legitimateToken = jwtTokenService.generateAccessToken(user);

        String[] parts = legitimateToken.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1] + ".tamperedSignatureValue";

        assertThatThrownBy(() -> jwtTokenService.parseClaims(tamperedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an otherwise-valid expired HS256 token is rejected")
    void expiredTokenIsRejected() {
        SecretKey serverKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
                .subject("expired@example.com")
                .claim("role", "AGENT")
                .issuedAt(Date.from(now.minus(Duration.ofHours(2))))
                .expiration(Date.from(now.minus(Duration.ofHours(1))))
                .signWith(serverKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseClaims(expiredToken))
                .isInstanceOf(JwtException.class);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
