package io.github.santhosh2013.supportsense.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression coverage for a real authorization gap found during the Phase 6 security
 * audit: {@code POST /api/auth/register} is unauthenticated ({@code permitAll}) and
 * previously bound a client-supplied {@code teamId} directly onto the new user with no
 * validation that the registrant should be allowed to join that team. Since {@link
 * io.github.santhosh2013.supportsense.ticket.persistence.TicketSpecifications#visibleTo}
 * grants an AGENT full read access to their team's ticket queue, this let any anonymous
 * caller self-assign into an arbitrary existing team and immediately inherit its ticket
 * visibility — narrow blast radius in A1 (no triage assigns tickets to teams yet), but the
 * same unchanged code would expose every ticket in the system the moment A2 triage starts
 * assigning teams. Fixed by rejecting any non-null {@code teamId} on registration with 400.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class RegistrationTeamAssignmentSecurityIT {

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
    @DisplayName("registering with a non-null teamId is rejected with 400, and no user row is created")
    void registrationWithTeamIdIsRejected() {
        String email = "team-assign-attack-" + System.nanoTime() + "@example.com";
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long existingTeamId = jdbc.queryForObject("SELECT id FROM teams LIMIT 1", Long.class);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Attacker", existingTeamId),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Integer userCount =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(userCount).isZero();
    }

    @Test
    @DisplayName("registering with a null teamId still succeeds, and the user has no team")
    void registrationWithoutTeamIdSucceeds() {
        String email = "team-assign-legit-" + System.nanoTime() + "@example.com";

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Legit Agent", null),
                AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().user().teamId()).isNull();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
