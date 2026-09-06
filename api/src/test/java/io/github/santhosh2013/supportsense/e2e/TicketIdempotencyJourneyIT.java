package io.github.santhosh2013.supportsense.e2e;

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
 * BR-A02 (idempotency on externalRef), observed purely through HTTP status codes and
 * response bodies — no repository/JDBC access. "Exactly one ticket observable afterward"
 * is proven by asserting both responses report the same ticket id, since there is no
 * HTTP endpoint to count rows by externalRef directly.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class TicketIdempotencyJourneyIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useApacheHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Test
    @DisplayName("posting the same externalRef twice sequentially returns 202 then 200 with the same ticket id, never 500")
    void sequentialDuplicatePostsConvergeToSameTicket() {
        String accessToken = registerAndGetAccessToken("idem-seq-" + System.nanoTime() + "@example.com");
        String externalRef = "ext-idem-seq-" + System.nanoTime();
        CreateTicketRequest request = new CreateTicketRequest(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null);

        ResponseEntity<TicketResponse> first = post(request, accessToken);
        ResponseEntity<TicketResponse> second = post(request, accessToken);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
    }

    @Test
    @DisplayName("posting the same externalRef concurrently from two threads converges to exactly one ticket id, never 500")
    void concurrentDuplicatePostsConvergeToSameTicket() throws InterruptedException {
        String accessToken = registerAndGetAccessToken("idem-conc-" + System.nanoTime() + "@example.com");
        String externalRef = "ext-idem-conc-" + System.nanoTime();
        CreateTicketRequest request = new CreateTicketRequest(
                externalRef, "subject", "body", TicketChannel.WEB, "customer@example.com", null);

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<List<ResponseEntity<TicketResponse>>> responses =
                new AtomicReference<>(java.util.Collections.synchronizedList(new java.util.ArrayList<>()));

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    responses.get().add(post(request, accessToken));
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
        List<ResponseEntity<TicketResponse>> results = responses.get();
        assertThat(results).hasSize(threadCount);
        // Never a 500 — every response must be 202 (winner) or 200 (existing ticket).
        assertThat(results).allMatch(
                r -> r.getStatusCode() == HttpStatus.ACCEPTED || r.getStatusCode() == HttpStatus.OK);
        // Exactly one 202 (the winner) and one 200 (the loser sees the winner's row).
        long acceptedCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count();
        long okCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        assertThat(acceptedCount).isEqualTo(1);
        assertThat(okCount).isEqualTo(1);
        // Both responses observe the same ticket id — exactly one ticket exists.
        long distinctTicketIds =
                results.stream().map(r -> r.getBody().id()).distinct().count();
        assertThat(distinctTicketIds).isEqualTo(1);
    }

    private ResponseEntity<TicketResponse> post(CreateTicketRequest request, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(
                url("/api/tickets"), HttpMethod.POST, new HttpEntity<>(request, headers), TicketResponse.class);
    }

    private String registerAndGetAccessToken(String email) {
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest(email, "correct-horse-battery-staple", "Idempotency Test", null),
                AuthResponse.class);
        return registerResponse.getBody().accessToken();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
