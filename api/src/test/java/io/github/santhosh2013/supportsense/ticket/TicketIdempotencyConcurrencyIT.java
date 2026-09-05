package io.github.santhosh2013.supportsense.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.santhosh2013.supportsense.auth.web.AuthResponse;
import io.github.santhosh2013.supportsense.auth.web.RegisterRequest;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import io.github.santhosh2013.supportsense.ticket.persistence.TicketChannel;
import io.github.santhosh2013.supportsense.ticket.web.CreateTicketRequest;
import io.github.santhosh2013.supportsense.ticket.web.TicketResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * BR-A02 under real concurrency: application-level "check then insert" is racy and NOT
 * sufficient — the database's unique index on external_ref is the actual guard. Two
 * simultaneous POSTs with the same externalRef must both succeed at the HTTP layer (one
 * 202, one 200 with the same ticket id — never a 500), and exactly one row must exist.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class TicketIdempotencyConcurrencyIT {

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
    @DisplayName("two simultaneous POSTs with the same externalRef never produce a 500 and never duplicate the row")
    void concurrentDuplicatePostsAreHandledSafely() throws InterruptedException {
        // POST /api/tickets requires an authenticated caller (sheet 06: "any"; ADR-0005:
        // machine ingestion uses a SERVICE-role user in A1) — a bare request with no
        // Authorization header is correctly rejected with 403 before it ever reaches the
        // controller, which is what this test originally hit.
        String accessToken = registerAndGetAccessToken();
        String externalRef = "ext-concurrent-dup-" + System.nanoTime();
        int concurrentRequests = 10;

        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);
        AtomicReference<List<HttpStatus>> statuses =
                new AtomicReference<>(java.util.Collections.synchronizedList(new java.util.ArrayList<>()));

        for (int i = 0; i < concurrentRequests; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setBearerAuth(accessToken);
                    HttpEntity<CreateTicketRequest> requestEntity = new HttpEntity<>(
                            new CreateTicketRequest(
                                    externalRef, "subject", "body", TicketChannel.WEB,
                                    "customer@example.com", null),
                            headers);
                    ResponseEntity<TicketResponse> response = restTemplate.exchange(
                            url("/api/tickets"), HttpMethod.POST, requestEntity, TicketResponse.class);
                    statuses.get().add((HttpStatus) response.getStatusCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).isTrue();
        // Never a 500 — every response must be a 202 (winner) or 200 (existing ticket).
        assertThat(statuses.get()).allMatch(
                status -> status == HttpStatus.ACCEPTED || status == HttpStatus.OK);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE external_ref = ?", Integer.class, externalRef);
        assertThat(rowCount).isEqualTo(1);
    }

    private String registerAndGetAccessToken() {
        String email = "ingestion-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Ingestion Test", null),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
