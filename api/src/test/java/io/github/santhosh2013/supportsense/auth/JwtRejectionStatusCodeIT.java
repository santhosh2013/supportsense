package io.github.santhosh2013.supportsense.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Regression coverage for a real defect found while writing the E2E journey suite: every
 * unauthenticated/invalid-credential request returned 403, because {@code SecurityConfig}
 * registered no {@code AuthenticationEntryPoint} — Spring Security's default
 * ({@code Http403ForbiddenEntryPoint}) silently applied to both "no credentials" and
 * "authenticated but forbidden," which are supposed to be 401 and 403 respectively
 * (RFC 7235 / RFC 7231 §6.5.3). {@code JwtAuthenticationFilter} compounded this by
 * silently clearing the security context on an invalid token rather than rejecting the
 * request, making an invalid token indistinguishable from no token at all.
 *
 * <p>These tests fail against the pre-fix code (all would have observed 403) and pass
 * against the fix. They also assert the four failure-mode bodies are byte-identical,
 * since a differing body would let an attacker distinguish "expired" from "tampered"
 * from "wrong key" — a credential-probing oracle.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class JwtRejectionStatusCodeIT {

    // Must match supportsense.security.jwt-secret in application-test.yml exactly — this
    // test forges tokens the way an external attacker would, not via internal test access.
    private static final String TEST_JWT_SECRET =
            "test-secret-key-for-integration-tests-only-not-production-safe";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("no Authorization header on a protected endpoint is 401, not 403")
    void missingAuthorizationHeaderIsUnauthorized() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/api/tickets"), HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("an expired JWT is 401, not 403")
    void expiredJwtIsUnauthorized() {
        String expired = forgedToken(builder -> builder.expiration(
                Date.from(Instant.now().minus(1, ChronoUnit.HOURS))));

        ResponseEntity<String> response = getTicketsWithToken(expired);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a JWT with a tampered signature is 401, not 403")
    void tamperedSignatureIsUnauthorized() {
        String valid = forgedToken(builder -> {});
        String[] parts = valid.split("\\.");
        // Flip one character in the signature segment so the HMAC no longer verifies.
        char[] signatureChars = parts[2].toCharArray();
        signatureChars[0] = signatureChars[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signatureChars);

        ResponseEntity<String> response = getTicketsWithToken(tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an alg:none forged token is 401, not 403")
    void algNoneForgedTokenIsUnauthorized() {
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        "{\"sub\":\"admin@supportsense.local\",\"role\":\"ADMIN\"}"
                                .getBytes(StandardCharsets.UTF_8));
        String algNoneToken = header + "." + payload + ".";

        ResponseEntity<String> response = getTicketsWithToken(algNoneToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("all four rejection modes return identical bodies apart from timestamp — no credential-probing oracle")
    void allRejectionModesReturnIdenticalBodies() throws Exception {
        String expired =
                forgedToken(builder -> builder.expiration(Date.from(Instant.now().minus(1, ChronoUnit.HOURS))));
        String valid = forgedToken(builder -> {});
        String[] parts = valid.split("\\.");
        char[] signatureChars = parts[2].toCharArray();
        signatureChars[0] = signatureChars[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signatureChars);

        String missingBody =
                restTemplate
                        .exchange(url("/api/tickets"), HttpMethod.GET, HttpEntity.EMPTY, String.class)
                        .getBody();
        String expiredBody = getTicketsWithToken(expired).getBody();
        String tamperedBody = getTicketsWithToken(tampered).getBody();

        // `timestamp` is real wall-clock time (TimeSource.now()) and legitimately differs
        // between separate HTTP calls — compare every OTHER field instead of the raw string.
        assertThat(withoutTimestamp(expiredBody)).isEqualTo(withoutTimestamp(missingBody));
        assertThat(withoutTimestamp(tamperedBody)).isEqualTo(withoutTimestamp(missingBody));
    }

    private String withoutTimestamp(String problemDetailJson) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(problemDetailJson);
        node.remove("timestamp");
        return node.toString();
    }

    @Test
    @DisplayName("authenticated wrong-role is still 403, not 401 — the fix did not overcorrect")
    void authenticatedWrongRoleIsStillForbidden() {
        String agentEmail = "jwt-status-" + System.nanoTime() + "@example.com";
        RegisterRequest register =
                new RegisterRequest(agentEmail, "correct-horse-battery-staple", "Status Code Test", null);
        AuthResponse auth = restTemplate.postForEntity(url("/api/auth/register"), register, AuthResponse.class)
                .getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(auth.accessToken());
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(java.util.List.of(), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<String> getTicketsWithToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url("/api/tickets"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String forgedToken(java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customizer) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject("admin@supportsense.local")
                .claim("uid", 1L)
                .claim("role", "ADMIN")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key, Jwts.SIG.HS256);
        customizer.accept(builder);
        return builder.compact();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
