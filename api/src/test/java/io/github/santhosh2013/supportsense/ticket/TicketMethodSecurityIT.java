package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression coverage for code-review Should-fix #1: {@code @PreAuthorize} was decorative
 * until {@code @EnableMethodSecurity} was added — no method-security advisor existed to
 * evaluate it. A test asserting only 200 for the correct role would have stayed green the
 * entire time the annotation was inert; these tests specifically assert 403 for the WRONG
 * role, which is the only assertion that can distinguish "enforced" from "decorative."
 *
 * <p>Uses full {@code @SpringBootTest}, which loads {@code SecurityConfig} (and therefore
 * {@code @EnableMethodSecurity}) into the test context — unlike a narrow {@code @WebMvcTest}
 * slice, which would not exercise this annotation at all.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class TicketMethodSecurityIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("an AGENT (wrong role) gets 403 from the bulk endpoint — proves @PreAuthorize is enforced, not decorative")
    void agentRoleIsForbiddenFromBulkEndpoint() {
        // Every self-registered user is AGENT (see AuthService.register) — there is no HTTP
        // path in A1 to become ADMIN/SERVICE, so this IS the wrong-role case by construction.
        String agentToken = registerAndGetAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(agentToken);
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(java.util.List.of(sampleRequest()), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an ADMIN-role caller is permitted through the bulk endpoint's @PreAuthorize check")
    void adminRoleIsPermittedThroughBulkEndpoint() {
        // Directly promotes a freshly-registered user to ADMIN via SQL, since A1 has no HTTP
        // role-promotion path — isolates the assertion to @PreAuthorize's role check, not to
        // any other layer.
        String email = "admin-promoted-" + System.nanoTime() + "@example.com";
        registerAndGetAccessTokenForEmail(email);
        new JdbcTemplate(dataSource).update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        // JWT claims are immutable after issuance. Re-login after promotion so the token
        // actually carries ADMIN rather than the AGENT role set at registration time.
        String token = loginAndGetAccessToken(email);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(java.util.List.of(sampleRequest()), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private CreateTicketRequest sampleRequest() {
        return new CreateTicketRequest(
                "ext-methodsec-" + System.nanoTime(), "subject", "body", TicketChannel.WEB,
                "customer@example.com", null);
    }

    private String registerAndGetAccessToken() {
        return registerAndGetAccessTokenForEmail("agent-methodsec-" + System.nanoTime() + "@example.com");
    }

    private String registerAndGetAccessTokenForEmail(String email) {
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Method Security Test", null),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private String loginAndGetAccessToken(String email) {
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                url("/api/auth/login"),
                new LoginRequest(email, "correct-horse-battery-staple"),
                AuthResponse.class);
        return loginResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
