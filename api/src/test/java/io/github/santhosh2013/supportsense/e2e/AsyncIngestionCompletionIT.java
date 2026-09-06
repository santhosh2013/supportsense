package io.github.santhosh2013.supportsense.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.LoginRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.time.Duration;
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
 * Async ingestion completion, observed purely via the new {@code TicketResponse.ingestionState}
 * field over HTTP — no DB polling. Uses the seeded ADMIN (only role that can see an
 * untriaged ticket, since a freshly-ingested ticket has team_id NULL). Bounded Awaitility
 * poll, never Thread.sleep.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class AsyncIngestionCompletionIT {

    private static final String SEEDED_ADMIN_EMAIL = "admin@supportsense.local";
    private static final String SEEDED_ADMIN_PASSWORD = "test-admin-password";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("a posted ticket's ingestionState eventually reaches DONE, observed by polling GET /api/tickets/{id}")
    void postedTicketIngestionStateReachesDone() {
        String adminToken = loginAsSeededAdmin();
        String externalRef = "ext-asyncdone-" + System.nanoTime();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        ResponseEntity<TicketResponse> createResponse = restTemplate.exchange(
                url("/api/tickets"),
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateTicketRequest(
                                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null),
                        headers),
                TicketResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long ticketId = createResponse.getBody().id();
        // Newly created, work not yet claimed.
        assertThat(createResponse.getBody().ingestionState()).isIn("PENDING", "PROCESSING", "DONE");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    ResponseEntity<TicketResponse> polled = restTemplate.exchange(
                            url("/api/tickets/" + ticketId),
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            TicketResponse.class);
                    assertThat(polled.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(polled.getBody().ingestionState()).isEqualTo("DONE");
                });
    }

    private String loginAsSeededAdmin() {
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                url("/api/auth/login"),
                new LoginRequest(SEEDED_ADMIN_EMAIL, SEEDED_ADMIN_PASSWORD),
                AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
