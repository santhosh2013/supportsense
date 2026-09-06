package io.github.santhosh2013.supportsense.e2ealt;

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

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class NegativeAuthMatrixAltIT {

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
    @DisplayName("wrong-role AGENT calling POST /api/tickets/bulk gets 403")
    void wrongRoleAgentIsForbiddenFromBulkEndpoint() {
        String agentToken = registerAndGetAccessToken("alt-negauth-wrongrole-" + System.nanoTime() + "@example.com");

        ResponseEntity<String> response = postBulk(List.of(sampleTicket()), agentToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("missing Authorization on protected endpoint is rejected with 401")
    void missingAuthorizationHeaderIsRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("expired JWT is rejected with 401")
    void expiredJwtIsRejected() {
        SecretKey serverKey = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
                .subject("alt-expired@example.com")
                .claim("uid", 1L)
                .claim("role", "AGENT")
                .issuedAt(Date.from(now.minus(Duration.ofHours(2))))
                .expiration(Date.from(now.minus(Duration.ofMinutes(1))))
                .signWith(serverKey, Jwts.SIG.HS256)
                .compact();

        ResponseEntity<String> response = getWithToken("/api/tickets", expiredToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("tampered signature is rejected with 401")
    void tamperedSignatureIsRejected() {
        String legitimateToken = registerAndGetAccessToken("alt-negauth-tamper-" + System.nanoTime() + "@example.com");
        String[] parts = legitimateToken.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1] + "." + "signatureTampered";

        ResponseEntity<String> response = getWithToken("/api/tickets", tamperedToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("alg:none forged token is rejected with 401")
    void algNoneForgedTokenIsRejected() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"attacker@example.com\",\"role\":\"ADMIN\"}");
        String forgedToken = header + "." + payload + ".";

        ResponseEntity<String> response = getWithToken("/api/tickets", forgedToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("refresh token reuse after rotation revokes the full token family")
    void refreshTokenReuseRevokesEntireFamily() {
        String email = "alt-negauth-theft-" + System.nanoTime() + "@example.com";
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

        ResponseEntity<String> replay =
                restTemplate.postForEntity(url("/api/auth/refresh"), new RefreshRequest(firstRefreshToken), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> descendantAlsoRevoked = restTemplate.postForEntity(
                url("/api/auth/refresh"), new RefreshRequest(secondRefreshToken), String.class);
        assertThat(descendantAlsoRevoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("existing but invisible ticket is returned as 404, never 403")
    void invisibleTicketReturns404NotFound() {
        // There is no black-box way to discover valid team IDs for a deterministic cross-team setup.
        // This uses the untriaged-invisibility property instead: newly ingested tickets are team-null,
        // and a fresh team-less AGENT cannot see them.
        String creatorToken = registerAndGetAccessToken("alt-negauth-creator-" + System.nanoTime() + "@example.com");
        HttpHeaders creatorHeaders = new HttpHeaders();
        creatorHeaders.setBearerAuth(creatorToken);
        ResponseEntity<TicketResponse> createResponse = restTemplate.exchange(
                url("/api/tickets"),
                HttpMethod.POST,
                new HttpEntity<>(sampleTicket(), creatorHeaders),
                TicketResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long ticketId = createResponse.getBody().id();

        String observerToken = registerAndGetAccessToken("alt-negauth-observer-" + System.nanoTime() + "@example.com");
        ResponseEntity<String> response = getWithToken("/api/tickets/" + ticketId, observerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private CreateTicketRequest sampleTicket() {
        return new CreateTicketRequest(
                "ext-alt-negauth-" + System.nanoTime(),
                "subject",
                "body",
                TicketChannel.WEB,
                "customer@example.com",
                null);
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
                new RegisterRequest(email, "correct-horse-battery-staple", "E2E Alt", null),
                AuthResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return registerResponse.getBody().accessToken();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
