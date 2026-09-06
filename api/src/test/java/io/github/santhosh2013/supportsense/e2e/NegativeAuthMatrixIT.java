package io.github.santhosh2013.supportsense.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RefreshRequest;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
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
 * Black-box negative-auth journeys, HTTP boundary only — no repository/EntityManager/bean
 * access. Every outcome here is observed exclusively through response status codes.
 *
 * <p><b>History:</b> the first blind run of this suite found that every filter-chain
 * rejection (missing/expired/tampered/forged-alg token) returned 403, because {@code
 * SecurityConfig} registered no {@code AuthenticationEntryPoint} and Spring Security's
 * default ({@code Http403ForbiddenEntryPoint}) applied uniformly to both "no credentials"
 * and "authenticated but forbidden." That was a real defect, not a test-design choice —
 * fixed via an explicit {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler} pair
 * in {@code SecurityConfig}, with a dedicated regression test in {@code
 * JwtRejectionStatusCodeIT}. This suite now asserts the corrected, RFC-7235-conformant
 * behavior: missing/invalid credentials are 401, authenticated-but-wrong-role is 403.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class NegativeAuthMatrixIT {

    // Matches src/test/resources/application-test.yml supportsense.security.jwt-secret.
    // Legitimate use per mission brief: forging attacker-simulation tokens for negative
    // security tests, not bypassing test isolation.
    private static final String TEST_JWT_SECRET =
            "test-secret-key-for-integration-tests-only-not-production-safe";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        // The JDK's HttpURLConnection has hardcoded retry logic for 401/407 responses that
        // throws HttpRetryException in streaming mode; Apache HttpClient5 does not.
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("BR: @PreAuthorize on POST /api/tickets/bulk rejects the wrong role (AGENT) with 403")
    void wrongRoleAgentIsForbiddenFromBulkEndpoint() {
        String agentToken = registerAndGetAccessToken("negauth-wrongrole-" + System.nanoTime() + "@example.com");

        ResponseEntity<String> response = postBulk(List.of(sampleTicket()), agentToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a protected endpoint with no Authorization header rejects with 401")
    void missingAuthorizationHeaderIsRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired JWT (signed with the real server secret, exp in the past) is rejected with 401")
    void expiredJwtIsRejected() {
        SecretKey serverKey = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
                .subject("expired-e2e@example.com")
                .claim("uid", 1L)
                .claim("role", "AGENT")
                .issuedAt(Date.from(now.minus(Duration.ofHours(2))))
                .expiration(Date.from(now.minus(Duration.ofHours(1))))
                .signWith(serverKey, Jwts.SIG.HS256)
                .compact();

        ResponseEntity<String> response = getWithToken("/api/tickets", expiredToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token with a tampered signature segment is rejected with 401")
    void tamperedSignatureIsRejected() {
        String legitimateToken = registerAndGetAccessToken("negauth-tamper-" + System.nanoTime() + "@example.com");
        String[] parts = legitimateToken.split("\\.");
        // Flip the signature: replace with a same-shaped but definitely-wrong value.
        String tamperedToken = parts[0] + "." + parts[1] + "." + "tamperedSignatureSegmentXYZ";

        ResponseEntity<String> response = getWithToken("/api/tickets", tamperedToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an alg:none forged token with an empty signature is rejected with 401")
    void algNoneForgedTokenIsRejected() {
        // Classic RFC 7519/8725 "none" attack: header {"alg":"none"}, forged payload
        // claiming ADMIN, and an empty signature segment.
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"attacker@example.com\",\"role\":\"ADMIN\"}");
        String forgedToken = header + "." + payload + ".";

        ResponseEntity<String> response = getWithToken("/api/tickets", forgedToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("replaying a rotated refresh token revokes the whole family — both the replay and the untouched descendant are 401")
    void refreshTokenReuseRevokesEntireFamily() {
        String email = "negauth-theft-" + System.nanoTime() + "@example.com";
        AuthResponse registration = restTemplate
                .postForEntity(
                        url("/api/auth/register"),
                        new RegisterRequest(email, "correct-horse-battery-staple", "Theft Test", null),
                        AuthResponse.class)
                .getBody();
        assertThat(registration).isNotNull();
        String firstRefreshToken = registration.refreshToken();

        ResponseEntity<AuthResponse> rotateOnce = restTemplate.postForEntity(
                url("/api/auth/refresh"), new RefreshRequest(firstRefreshToken), AuthResponse.class);
        assertThat(rotateOnce.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondRefreshToken = rotateOnce.getBody().refreshToken();
        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);

        // Half 1: replaying the already-rotated original token is theft.
        ResponseEntity<String> replay = restTemplate.postForEntity(
                url("/api/auth/refresh"), new RefreshRequest(firstRefreshToken), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Half 2: the descendant token, though never itself replayed, is now also revoked.
        ResponseEntity<String> descendantAlsoRevoked = restTemplate.postForEntity(
                url("/api/auth/refresh"), new RefreshRequest(secondRefreshToken), String.class);
        assertThat(descendantAlsoRevoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/tickets/{id} for an existing-but-invisible ticket is 404, never 403 (existence non-leakage)")
    void invisibleTicketReturns404NotFound() {
        // True cross-team could not be constructed black-box: there is no team-listing
        // endpoint, and register's optional teamId cannot be resolved to a *known-valid,
        // different-from-mine* id without inventing one or reading the DB. Using the
        // untriaged-invisibility case instead, per the mission brief's explicit fallback:
        // a freshly ingested ticket has team_id NULL (no triage exists in A1) and is
        // invisible to a plain, team-less freshly-registered AGENT.
        String creatorToken = registerAndGetAccessToken("negauth-creator-" + System.nanoTime() + "@example.com");
        String externalRef = "ext-negauth-invisible-" + System.nanoTime();
        HttpHeaders creatorHeaders = new HttpHeaders();
        creatorHeaders.setBearerAuth(creatorToken);
        ResponseEntity<TicketResponse> createResponse = restTemplate.exchange(
                url("/api/tickets"),
                HttpMethod.POST,
                new HttpEntity<>(sampleTicketWithRef(externalRef), creatorHeaders),
                TicketResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long ticketId = createResponse.getBody().id();

        // No teamId — a team-less AGENT sees nothing (TicketSpecifications.visibleTo).
        String observerToken = registerAndGetAccessToken("negauth-observer-" + System.nanoTime() + "@example.com");
        ResponseEntity<String> response = getWithToken("/api/tickets/" + ticketId, observerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private CreateTicketRequest sampleTicket() {
        return sampleTicketWithRef("ext-negauth-" + System.nanoTime());
    }

    private CreateTicketRequest sampleTicketWithRef(String externalRef) {
        return new CreateTicketRequest(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null);
    }

    private ResponseEntity<String> postBulk(List<CreateTicketRequest> items, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                url("/api/tickets/bulk"), HttpMethod.POST, new HttpEntity<>(items, headers), String.class);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String registerAndGetAccessToken(String email) {
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "E2E Test", null),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
