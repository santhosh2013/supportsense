package io.github.santhosh2013.supportsense.ticket;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.time.Duration;
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
 * Proves the AFTER_COMMIT contract is real, not assumed: a ticket must actually reach DONE
 * asynchronously (the listener fired and dispatched), and the dispatched worker must see a
 * row that is genuinely readable from another connection — not a phantom read of an
 * uncommitted transaction.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class TicketIngestionAfterCommitIT {

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
    @DisplayName("a successfully committed ticket is asynchronously dispatched and reaches DONE")
    void committedTicketReachesDone() {
        String accessToken = registerAndGetAccessToken();
        String externalRef = "ext-aftercommit-" + System.nanoTime();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<CreateTicketRequest> requestEntity = new HttpEntity<>(
                new CreateTicketRequest(
                        externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                headers);

        ResponseEntity<TicketResponse> response =
                restTemplate.exchange(url("/api/tickets"), HttpMethod.POST, requestEntity, TicketResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long ticketId = response.getBody().id();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Polls from a SEPARATE connection than the one that inserted the row — proves the
        // AFTER_COMMIT dispatch (and the worker it triggers) genuinely sees committed data,
        // not merely data visible within the same transaction/connection.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String state = jdbc.queryForObject(
                    "SELECT ingestion_state FROM tickets WHERE id = ?", String.class, ticketId);
            assertThat(state).isEqualTo("DONE");
        });
    }

    private String registerAndGetAccessToken() {
        String email = "aftercommit-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "AfterCommit Test", null),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
