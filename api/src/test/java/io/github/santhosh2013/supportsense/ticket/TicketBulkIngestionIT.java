package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.BulkIngestResponse;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import java.util.ArrayList;
import java.util.List;
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
 * Regression coverage for the bulk endpoint added in code review (previously missing
 * entirely — a genuine Blocker, since the requirements/execution plan require it and no
 * test could have caught the absence). Fails to compile against the pre-fix codebase since
 * POST /api/tickets/bulk and BulkIngestResponse did not exist.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class TicketBulkIngestionIT {

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
    @DisplayName("bulk ingestion reports a per-item outcome, including a duplicate and a rejection")
    void bulkIngestionReportsHonestPerItemOutcomes() {
                String accessToken = registerPromoteAndLoginAsAdmin();
        String sharedRef = "ext-bulk-dup-" + System.nanoTime();

        // Pre-existing ticket so one bulk item collides as a genuine BR-A02 duplicate.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        restTemplate.exchange(
                url("/api/tickets"),
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateTicketRequest(
                                sharedRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                        headers),
                Object.class);

        List<CreateTicketRequest> batch = List.of(
                new CreateTicketRequest(
                        "ext-bulk-new-" + System.nanoTime(), "s", "b", TicketChannel.WEB, "a@example.com", null),
                new CreateTicketRequest(sharedRef, "s", "b", TicketChannel.WEB, "a@example.com", null),
                new CreateTicketRequest(
                        "ext-bulk-invalid-" + System.nanoTime(), "", "b", TicketChannel.WEB, "a@example.com", null));

        ResponseEntity<BulkIngestResponse> response = restTemplate.exchange(
                url("/api/tickets/bulk"), HttpMethod.POST, new HttpEntity<>(batch, headers), BulkIngestResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        BulkIngestResponse body = response.getBody();
        assertThat(body.accepted()).isEqualTo(1);
        assertThat(body.duplicates()).isEqualTo(1);
        assertThat(body.rejected()).isEqualTo(1);
        assertThat(body.items()).hasSize(3);
        assertThat(body.items().get(0).outcome()).isEqualTo(BulkIngestResponse.Outcome.ACCEPTED);
        assertThat(body.items().get(1).outcome()).isEqualTo(BulkIngestResponse.Outcome.DUPLICATE);
        assertThat(body.items().get(2).outcome()).isEqualTo(BulkIngestResponse.Outcome.REJECTED);
    }

    @Test
    @DisplayName("a bulk request exceeding the max batch size is rejected with 400, not silently truncated")
    void oversizedBulkRequestIsRejectedNotTruncated() {
                String accessToken = registerPromoteAndLoginAsAdmin();

        List<CreateTicketRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(new CreateTicketRequest(
                    "ext-bulk-oversize-" + System.nanoTime() + "-" + i,
                    "s", "b", TicketChannel.WEB, "a@example.com", null));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/tickets/bulk"), HttpMethod.POST, new HttpEntity<>(tooMany, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE external_ref LIKE 'ext-bulk-oversize-%'", Integer.class);
        assertThat(count).isZero();
    }

        private String registerPromoteAndLoginAsAdmin() {
        String email = "bulk-" + System.nanoTime() + "@example.com";
                restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Bulk Test", null),
                AuthResponse.class);
                new JdbcTemplate(dataSource).update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);

                // Registration always issues an AGENT token. Re-login after the test-only database
                // promotion so this request carries the ADMIN role that the endpoint now enforces.
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
